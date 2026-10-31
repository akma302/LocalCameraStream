package dev.localstream.client.impl

import android.content.Context
import android.os.SystemClock
import dev.localstream.client.api.*
import dev.localstream.media.*
import dev.localstream.network.*
import dev.localstream.protocol.*
import dev.localstream.video.api.VideoOutput
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DefaultViewerSession(private val context: Context) : ViewerSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val mutableServers = MutableStateFlow<List<ServerEndpoint>>(emptyList())
    override val servers = mutableServers.asStateFlow()
    private val mutableState = MutableStateFlow(ViewerState())
    override val state = mutableState.asStateFlow()
    private val mutableVideo = MutableStateFlow<VideoOutput?>(null)
    override val video = mutableVideo.asStateFlow()
    private var discovery: Job? = null
    private var watching: Job? = null
    @Volatile private var requestStop: (() -> Unit)? = null
    private var desired: Pair<ServerEndpoint, String>? = null
    private var visible = true
    private val clientId = UUID.randomUUID().toString()

    override fun startDiscovery() {
        if (discovery?.isActive == true) return
        discovery = scope.launch {
            while (isActive) {
                try {
                    LocalDiscovery(context).discover().collect { list ->
                        mutableServers.value = list.map { ServerEndpoint(it.id, it.name, it.host, it.port) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { delay(2_000) }
            }
        }
    }
    override fun stopDiscovery() { discovery?.cancel(); discovery = null; mutableServers.value = emptyList() }
    override suspend fun connect(server: ServerEndpoint, accessCode: String) = lifecycle.withLock {
        stopCurrent()
        desired = server to accessCode.trim()
        if (visible) watching = scope.launch { watch(server, accessCode.trim()) }
    }
    override suspend fun disconnect() = lifecycle.withLock {
        desired = null; stopCurrent()
        mutableState.value = ViewerState()
    }
    override suspend fun setVisible(visible: Boolean) = lifecycle.withLock {
        this.visible = visible
        if (!visible) {
            stopCurrent()
            if (desired != null) mutableState.value = mutableState.value.copy(phase = ViewerPhase.PAUSED, message = "Просмотр приостановлен")
        } else if (watching?.isActive != true) {
            desired?.let { (server, code) -> watching = scope.launch { watch(server, code) } }
        }
    }
    override suspend fun close() { disconnect(); stopDiscovery(); scope.cancel() }
    private suspend fun stopCurrent() {
        val job = watching ?: return
        requestStop?.invoke()
        if (withTimeoutOrNull(2_000) { job.join(); true } != true) job.cancelAndJoin()
        watching = null
    }

    private sealed interface Event {
        data object Tick : Event
        data object Stop : Event
        data class Network(val snapshot: LocalNetworkMonitor.Snapshot) : Event
        data class Open(val epoch: Int, val socket: DefaultClientWebSocketSession) : Event
        data class Message(val epoch: Int, val message: Signal) : Event
        data class Closed(val epoch: Int) : Event
        data class Ice(val generation: String, val candidate: IceCandidate) : Event
    }

    private suspend fun watch(initial: ServerEndpoint, accessCode: String) = coroutineScope {
        val runtime = RtcRuntime(context)
        val http = HttpClient(OkHttp) {
            install(WebSockets)
            engine { config { connectTimeout(5, TimeUnit.SECONDS); pingInterval(3, TimeUnit.SECONDS); readTimeout(0, TimeUnit.MILLISECONDS) } }
        }
        val events = Channel<Event>(256)
        requestStop = { events.trySend(Event.Stop) }
        var socket: DefaultClientWebSocketSession? = null
        var socketJob: Job? = null
        var opening = false
        var epoch = 0
        var token = accessCode
        var streamId = ""
        var resolvedServerId = initial.id
        var generation = ""
        var peer: RtcPeer? = null
        var iceJob: Job? = null
        var networkAvailable = false
        var nextAttempt = 0L
        var attempt = 0
        var stage = 0
        var deadline = 0L
        var lastProgress = SystemClock.elapsedRealtime()
        var lastFrames = 0L
        var disconnectedSince: Long? = null
        var stableSince: Long? = null
        var terminal = false
        var authenticated = false
        var serverFull = false
        fun status(phase: ViewerPhase, message: String) {
            if (mutableState.value.phase != phase) android.util.Log.i("ViewerSession", "phase=$phase")
            mutableState.value = mutableState.value.copy(phase = phase, message = message, server = initial, attempt = attempt)
        }
        suspend fun send(message: Signal): Boolean {
            val current = socket ?: return false
            return try { withTimeout(3_000) { current.send(wireJson.encodeToString(message)) }; true }
            catch (e: CancellationException) { if (!currentCoroutineContext().isActive) throw e; false }
            catch (_: Exception) { false }
        }
        fun replacePeer(newGeneration: String) {
            iceJob?.cancel(); peer?.close()
            generation = newGeneration
            val fresh = RtcPeer(runtime).also { peer = it }
            mutableVideo.value = fresh.output
            val capturedGeneration = generation
            iceJob = launch { for (candidate in fresh.candidates) events.send(Event.Ice(capturedGeneration, candidate)) }
            lastFrames = 0; lastProgress = SystemClock.elapsedRealtime(); stableSince = null; disconnectedSince = null
        }
        val ticks = launch { while (isActive) { events.send(Event.Tick); delay(500) } }
        val networks = launch { LocalNetworkMonitor(context).changes().collect { events.send(Event.Network(it)) } }
        status(ViewerPhase.CONNECTING, "Подключение…")
        try {
            loop@ for (event in events) {
                val now = SystemClock.elapsedRealtime()
                when (event) {
                    Event.Stop -> break@loop
                    is Event.Network -> {
                        val wasAvailable = networkAvailable
                        networkAvailable = event.snapshot.available
                        if (networkAvailable && !wasAvailable) nextAttempt = 0
                        if (!networkAvailable) status(ViewerPhase.WAITING_FOR_NETWORK, "Ожидаем Wi-Fi…")
                    }
                    is Event.Open -> {
                        if (event.epoch != epoch) { event.socket.close(); continue@loop }
                        socket = event.socket; opening = false; authenticated = false
                        send(Signal(SignalType.JOIN, clientId = clientId, token = token, accessCode = accessCode,
                            streamId = streamId, generation = generation))
                        deadline = now + 10_000
                    }
                    is Event.Closed -> if (event.epoch == epoch) {
                        socket = null; opening = false; authenticated = false; stableSince = null
                        nextAttempt = now + RecoveryPolicy.backoffMs(attempt++, Random.nextDouble())
                        if (peer?.lastFrameAt?.let { now - it < 2_000 } != true)
                            status(ViewerPhase.RECONNECTING, if (serverFull) "Сервер заполнен. Ожидаем свободное место…" else "Восстанавливаем соединение…")
                    }
                    is Event.Ice -> if (event.generation == generation && authenticated) {
                        send(Signal(SignalType.ICE, generation = generation, candidate = event.candidate.sdp,
                            mid = event.candidate.sdpMid.orEmpty(), line = event.candidate.sdpMLineIndex))
                    }
                    is Event.Message -> {
                        if (event.epoch != epoch) continue@loop
                        val m = event.message
                        when (m.type) {
                            SignalType.ERROR -> {
                                if (m.code == "FULL") {
                                    serverFull = true
                                    status(ViewerPhase.RECONNECTING, "Сервер заполнен. Ожидаем свободное место…")
                                } else {
                                    terminal = true
                                    status(if (m.code == "ENDED") ViewerPhase.ENDED else ViewerPhase.ERROR, m.message)
                                    break@loop
                                }
                            }
                            SignalType.ENDED -> { terminal = true; status(ViewerPhase.ENDED, "Трансляция завершена"); break@loop }
                            SignalType.WELCOME -> {
                                serverFull = false
                                authenticated = true; token = m.token; streamId = m.streamId
                                resolvedServerId = m.serverId.ifEmpty { resolvedServerId }
                                if (peer == null || generation != m.generation) {
                                    replacePeer(m.generation); stage = 0
                                    status(ViewerPhase.NEGOTIATING, "Согласование видеопотока…")
                                }
                            }
                            SignalType.OFFER -> if (m.generation == generation) {
                                try {
                                    val answer = withTimeout(8_000) { peer!!.answer(m.sdp) }
                                    send(Signal(SignalType.ANSWER, generation = generation, sdp = answer))
                                    lastProgress = now
                                    status(ViewerPhase.WAITING_FOR_VIDEO, "Ожидаем видео…")
                                } catch (e: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    send(Signal(SignalType.RECREATE, generation = generation))
                                    stage = 2; deadline = SystemClock.elapsedRealtime() + 12_000
                                    status(ViewerPhase.RECONNECTING, "Повторное согласование видео…")
                                }
                            }
                            SignalType.ICE -> if (m.generation == generation) {
                                peer?.addIce(IceCandidate(m.mid, m.line, m.candidate))
                            }
                        }
                    }
                    Event.Tick -> {
                        val current = peer
                        val frames = current?.receivedFrames ?: 0L
                        if (frames > lastFrames) {
                            val count = frames - lastFrames; lastFrames = frames; lastProgress = now
                            stage = 0; disconnectedSince = null
                            if (authenticated) {
                                if (stableSince == null) stableSince = now
                                if (now - stableSince!! >= 5_000) attempt = 0
                            } else stableSince = null
                            mutableState.value = ViewerState(ViewerPhase.PLAYING,
                                if (socket == null) "Видео · восстанавливаем управление…" else "Прямой эфир",
                                initial, frames, (count * 2).toInt(), attempt)
                        }
                        if (current?.state?.value == PeerConnection.PeerConnectionState.DISCONNECTED) {
                            if (disconnectedSince == null) disconnectedSince = now
                        } else disconnectedSince = null
                        if (socket == null && !opening && networkAvailable && now >= nextAttempt) {
                            opening = true; epoch++; val openingEpoch = epoch
                            val endpoint = servers.value.firstOrNull { it.id == resolvedServerId } ?: initial
                            socketJob?.cancel()
                            socketJob = launch {
                                var connected: DefaultClientWebSocketSession? = null
                                try {
                                    connected = withTimeout(7_000) { http.webSocketSession {
                                        url { protocol = URLProtocol.WS; host = endpoint.host; port = endpoint.port; encodedPath = "/session" }
                                    } }
                                    events.send(Event.Open(openingEpoch, connected))
                                    for (frame in connected.incoming) if (frame is Frame.Text) {
                                        require(frame.data.size <= 128 * 1024) { "Слишком большое управляющее сообщение" }
                                        events.send(Event.Message(openingEpoch, wireJson.decodeFromString<Signal>(frame.readText())))
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { android.util.Log.w("ViewerSession", "WebSocket open/read failed: ${e.javaClass.simpleName}: ${e.message}") }
                                finally { withContext(NonCancellable) {
                                    runCatching { withTimeout(500) { connected?.close() } }
                                    events.trySend(Event.Closed(openingEpoch))
                                } }
                            }
                        }
                        if (socket != null && !authenticated && now >= deadline) socketJob?.cancel()
                        if (!networkAvailable && now - lastProgress >= 1_000)
                            status(ViewerPhase.WAITING_FOR_NETWORK, "Ожидаем Wi-Fi…")
                        if (authenticated && current != null) {
                            val action = RecoveryPolicy.action(now, lastProgress,
                                current.state.value == PeerConnection.PeerConnectionState.FAILED,
                                disconnectedSince, stage, deadline, networkAvailable)
                            when (action) {
                                RecoveryPolicy.Action.RESTART_ICE -> {
                                    if (send(Signal(SignalType.RESTART, generation = generation))) {
                                        stage = 1; deadline = now + 10_000; stableSince = null
                                        status(ViewerPhase.RECONNECTING, "Восстанавливаем видеопоток…")
                                    }
                                }
                                RecoveryPolicy.Action.RECREATE_PEER -> {
                                    if (send(Signal(SignalType.RECREATE, generation = generation))) {
                                        stage = 2; deadline = now + 12_000; stableSince = null
                                        status(ViewerPhase.RECONNECTING, "Повторное подключение видео…")
                                    }
                                }
                                RecoveryPolicy.Action.NONE -> {}
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status(ViewerPhase.ERROR, e.message ?: "Ошибка просмотра") }
        finally {
            withContext(NonCancellable) {
                runCatching { withTimeout(500) {
                    socket?.send(wireJson.encodeToString(Signal(SignalType.LEAVE, generation = generation)))
                    socket?.flush()
                    delay(50)
                } }
                ticks.cancel(); networks.cancel(); socketJob?.cancelAndJoin(); iceJob?.cancelAndJoin()
                mutableVideo.value = null; peer?.close(); http.close(); runtime.close(); events.close()
                requestStop = null
                if (terminal) desired = null
            }
        }
    }
}
