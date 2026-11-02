package dev.localstream.server

import android.content.Context
import android.os.SystemClock
import dev.localstream.camera.api.*
import dev.localstream.media.*
import dev.localstream.network.LocalDiscovery
import dev.localstream.protocol.*
import dev.localstream.video.api.VideoOutput
import io.ktor.server.application.*
import io.ktor.server.netty.*
import dev.localstream.network.SignalingIdentity
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.IceCandidate
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class HostState(val phase: String = "Остановлено", val running: Boolean = false, val starting: Boolean = false,
    val port: Int = 8765, val accessCode: String = "", val fingerprint: String = "", val viewers: Int = 0, val error: String? = null)

class HostController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val peersLock = Mutex()
    private var joins = JoinLimiter(SystemClock::elapsedRealtime)
    private val peers = mutableMapOf<String, Viewer>()
    private val mutableState = MutableStateFlow(HostState())
    val state = mutableState.asStateFlow()
    private val mutablePreview = MutableStateFlow<VideoOutput?>(null)
    val preview = mutablePreview.asStateFlow()
    private var runtime: RtcRuntime? = null
    private var source: CameraVideoSource? = null
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var advertisement: AutoCloseable? = null
    private var cleanup: Job? = null
    private var streamId = ""
    private val serverId = context.getSharedPreferences("host", Context.MODE_PRIVATE).let {
        it.getString("id", null) ?: UUID.randomUUID().toString().also { id -> it.edit().putString("id", id).apply() }
    }

    suspend fun start(api: CameraApi) = withContext(Dispatchers.IO) { lifecycle.withLock {
        if (mutableState.value.running) return@withLock
        mutableState.value = HostState(phase = "Запуск камеры…", starting = true)
        try {
            val cameras = api.listCameras()
            val camera = cameras.firstOrNull { it.facing == LensFacing.BACK } ?: cameras.firstOrNull()
                ?: error("Нет доступных камер")
            val config = CaptureConfig(camera.id, api.getCapabilities(camera.id).preferredProfile())
            val rtc = RtcRuntime(context).also { runtime = it }
            val producer = CameraVideoSource(rtc, api).also { source = it }
            producer.start(config, camera)
            android.util.Log.i("HostSession", "Camera capture ready")
            streamId = UUID.randomUUID().toString()
            val code = (100000 + SecureRandom().nextInt(900000)).toString()
            joins = JoinLimiter(SystemClock::elapsedRealtime)
            val identity = SignalingIdentity()
            mutableState.value = HostState(phase = "Трансляция", running = true, accessCode = code, fingerprint = identity.fingerprint)
            mutablePreview.value = producer.output
            // Own the long-lived engine in the host scope, not in start()'s withContext job.
            engine = scope.signalingServer(identity) { handleSocket(this) }.also { it.start(wait = false) }
            android.util.Log.i("HostSession", "Signaling engine started")
            // Wait for bind errors before advertising a working server.
            withTimeout(10_000) { engine!!.engine.resolvedConnectors() }
            android.util.Log.i("HostSession", "Signaling socket bound")
            advertisement = LocalDiscovery(context).advertise(serverId, "Camera-${android.os.Build.MODEL}", 8765)
            cleanup = scope.launch {
                while (isActive) {
                    delay(1_000)
                    peersLock.withLock {
                        val expired = peers.values.filter {
                            it.socket == null && SystemClock.elapsedRealtime() - it.detachedAt > 30_000 &&
                                it.peer.state.value != PeerConnection.PeerConnectionState.CONNECTED
                        }
                        expired.forEach { peers.remove(it.id); it.close() }
                        updateCount()
                    }
                }
            }
        } catch (e: Throwable) {
            dispose()
            mutableState.value = HostState(phase = "Ошибка запуска", error = e.message)
            throw e
        }
    } }

    suspend fun stop() = withContext(Dispatchers.IO) { lifecycle.withLock {
        peersLock.withLock { peers.values.forEach { it.send(Signal(SignalType.ENDED, streamId = streamId)) } }
        delay(100)
        dispose()
        mutableState.value = HostState()
    } }

    private suspend fun dispose() = withContext(NonCancellable) {
        advertisement?.close(); advertisement = null
        cleanup?.cancelAndJoin(); cleanup = null
        engine?.stop(100, 1_000); engine = null
        peersLock.withLock { peers.values.forEach { it.close() }; peers.clear() }
        mutablePreview.value = null
        source?.close(); source = null
        runtime?.close(); runtime = null
    }

    private suspend fun handleSocket(socket: DefaultWebSocketServerSession) {
        var viewer: Viewer? = null
        val routeId = UUID.randomUUID().toString()
        val outbox = Channel<Signal>(64)
        val writer = socket.launch { for (message in outbox) socket.send(wireJson.encodeToString(message)) }
        try {
            val first = withTimeout(6_000) { (socket.incoming.receive() as? Frame.Text)?.readText() } ?: return
            val join = wireJson.decodeFromString<Signal>(first)
            if (join.type != SignalType.JOIN || join.version != PROTOCOL_VERSION || join.clientId.length !in 1..80) {
                socket.send(wireJson.encodeToString(Signal(SignalType.ERROR, code = "PROTOCOL", message = "Несовместимый протокол"))); return
            }
            val rejection: String? = peersLock.withLock {
                val old = peers[join.clientId]
                when {
                    !joins.acquire() -> "RATE_LIMIT"
                    !mutableState.value.running -> "ENDED"
                    join.streamId.isNotEmpty() && join.streamId != streamId -> "ENDED"
                    old != null && join.token != old.token -> "AUTH"
                    old == null && join.token != mutableState.value.accessCode && join.accessCode != mutableState.value.accessCode -> "AUTH"
                    old == null && peers.size >= MAX_VIEWERS -> "FULL"
                    else -> {
                        val v = old ?: Viewer(join.clientId, UUID.randomUUID().toString()).also { peers[it.id] = it }
                        val previousSocket = v.socket
                        if (previousSocket != null) scope.launch { withTimeoutOrNull(500) { previousSocket.close(CloseReason(CloseReason.Codes.NORMAL, "Session resumed")) } }
                        v.socket = socket; v.routeId = routeId; v.outbox = outbox
                        viewer = v; updateCount(); null
                    }
                }
            }
            if (rejection != null) {
                socket.send(wireJson.encodeToString(Signal(SignalType.ERROR, code = rejection, message = when (rejection) {
                    "AUTH" -> "Неверный код доступа или сессия устарела"
                    "FULL" -> "Уже подключено пять зрителей"
                    "RATE_LIMIT" -> "Слишком много попыток входа. Повторите через минуту"
                    else -> "Трансляция завершена"
                }))); return
            }
            val v = viewer!!
            v.send(Signal(SignalType.WELCOME, serverId = serverId, streamId = streamId, token = v.token, generation = v.generation))
            if (join.generation != v.generation) v.commands.withLock { v.negotiate(false) }
            for (frame in socket.incoming) {
                if (frame !is Frame.Text || v.routeId != routeId) continue
                val message = wireJson.decodeFromString<Signal>(frame.readText())
                if (message.type == SignalType.LEAVE) {
                    peersLock.withLock { if (v.routeId == routeId) { peers.remove(v.id); v.close(); updateCount() } }
                    break
                }
                if (message.generation != v.generation) continue
                v.commands.withLock {
                    if (v.routeId != routeId) return@withLock
                    when (message.type) {
                        SignalType.ANSWER -> withTimeout(8_000) { v.peer.setRemote(SessionDescription.Type.ANSWER, message.sdp) }
                        SignalType.ICE -> v.peer.addIce(IceCandidate(message.mid, message.line, message.candidate))
                        SignalType.RESTART -> v.negotiate(true)
                        SignalType.RECREATE -> {
                            v.replacePeer()
                            v.send(Signal(SignalType.WELCOME, serverId = serverId, streamId = streamId, token = v.token, generation = v.generation))
                            v.negotiate(false)
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("HostSession", "Signaling failed: ${e.javaClass.simpleName}: ${e.message}") }
        finally {
            peersLock.withLock {
                viewer?.takeIf { it.routeId == routeId }?.let {
                    it.socket = null; it.outbox = null; it.detachedAt = SystemClock.elapsedRealtime()
                }
            }
            outbox.close(); writer.cancelAndJoin()
        }
    }
    private fun updateCount() { mutableState.value = mutableState.value.copy(viewers = peers.size) }

    private inner class Viewer(val id: String, val token: String) {
        @Volatile var generation = UUID.randomUUID().toString()
        var peer = RtcPeer(runtime!!, source!!.track)
        @Volatile var socket: DefaultWebSocketServerSession? = null
        @Volatile var routeId = ""
        @Volatile var outbox: Channel<Signal>? = null
        var detachedAt = 0L
        val commands = Mutex()
        private var candidates = forwardCandidates()
        fun send(message: Signal) { outbox?.trySend(message) }
        private fun forwardCandidates(): Job = scope.launch {
            val currentPeer = peer; val currentGeneration = generation
            for (ice in currentPeer.candidates) commands.withLock {
                // An offer must be queued before candidates from its ICE restart.
                if (currentPeer === peer) send(Signal(SignalType.ICE, generation = currentGeneration,
                    candidate = ice.sdp, mid = ice.sdpMid.orEmpty(), line = ice.sdpMLineIndex))
            }
        }
        suspend fun negotiate(restart: Boolean) {
            val sdp = withTimeout(8_000) { peer.offer(restart) }
            send(Signal(SignalType.OFFER, generation = generation, sdp = sdp))
        }
        fun replacePeer() {
            candidates.cancel(); peer.close(); generation = UUID.randomUUID().toString()
            peer = RtcPeer(runtime!!, source!!.track); candidates = forwardCandidates()
        }
        fun close() { candidates.cancel(); outbox?.close(); peer.close(); scope.launch { runCatching { withTimeout(500) { socket?.close() } } } }
    }
}
