package dev.localstream.client.impl

import dev.localstream.client.api.*
import dev.localstream.protocol.*
import dev.localstream.video.api.VideoOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Serial event loop; Android/WebRTC/Ktor are adapters rather than state-machine dependencies. */
internal class ViewerWorker(
    private val platform: ViewerPlatform,
    private val servers: StateFlow<List<ServerEndpoint>>,
    private val mutableState: MutableStateFlow<ViewerState>,
    private val mutableVideo: MutableStateFlow<VideoOutput?>,
    private val clientId: String,
    private val events: Channel<Event> = Channel(256),
    private val registerStop: (((() -> Unit)?) -> Unit),
) {
    internal sealed interface Event {
        data object Tick : Event
        data object Stop : Event
        data class Network(val available: Boolean) : Event
        data class Open(val epoch: Int, val socket: ViewerSocket) : Event
        data class Message(val epoch: Int, val message: Signal) : Event
        data class Closed(val epoch: Int) : Event
        data class Untrusted(val epoch: Int) : Event
        data class Ice(val generation: String, val candidate: ViewerIce) : Event
    }

    suspend fun run(initial: ServerEndpoint, accessCode: String): Boolean = coroutineScope {
        var runtime: ViewerRuntime? = null
        var transport: ViewerTransport? = null
        registerStop { events.trySend(Event.Stop) }
        var socket: ViewerSocket? = null
        var socketJob: Job? = null
        var opening = false
        var epoch = 0
        var token = accessCode
        var streamId = ""
        var resolvedServerId = initial.id
        var generation = ""
        var peer: ViewerPeer? = null
        var iceJob: Job? = null
        var networkAvailable = false
        var nextAttempt = 0L
        var admissionRetryAt = 0L
        var attempt = 0
        var stage = 0
        var deadline = 0L
        var lastProgress = platform.now()
        var lastFrames = 0L
        var disconnectedSince: Long? = null
        var stableSince: Long? = null
        var terminal = false
        var authenticated = false
        var serverFull = false
        fun status(phase: ViewerPhase, message: String) {
            if (mutableState.value.phase != phase) platform.log("phase=$phase")
            mutableState.value = mutableState.value.copy(phase = phase, message = message, server = initial, attempt = attempt)
        }
        suspend fun send(message: Signal): Boolean {
            val current = socket ?: return false
            return try { withTimeout(3_000) { current.send(message) }; true }
            catch (e: CancellationException) { if (!currentCoroutineContext().isActive) throw e; false }
            catch (_: Exception) { false }
        }
        fun replacePeer(newGeneration: String) {
            iceJob?.cancel(); peer?.close()
            generation = newGeneration
            val fresh = runtime!!.createPeer().also { peer = it }
            mutableVideo.value = fresh.output
            val capturedGeneration = generation
            iceJob = launch { for (candidate in fresh.candidates) events.send(Event.Ice(capturedGeneration, candidate)) }
            lastFrames = 0; lastProgress = platform.now(); stableSince = null; disconnectedSince = null
        }
        val ticks = launch { while (isActive) { events.send(Event.Tick); delay(500) } }
        val networks = launch { platform.networks().collect { events.send(Event.Network(it)) } }
        status(ViewerPhase.CONNECTING, "Подключение…")
        try {
            runtime = platform.createRuntime()
            transport = platform.createTransport(initial)
            loop@ for (event in events) {
                val now = platform.now()
                when (event) {
                    Event.Stop -> break@loop
                    is Event.Untrusted -> if (event.epoch == epoch) {
                        terminal = true
                        status(ViewerPhase.ERROR, "Сертификат сервера не прошёл проверку. Сверьте отпечаток на сервере.")
                        break@loop
                    }
                    is Event.Network -> {
                        val wasAvailable = networkAvailable
                        networkAvailable = event.available
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
                        nextAttempt = now + RecoveryPolicy.backoffMs(attempt++, platform.jitter())
                        if (peer?.lastFrameAt?.let { now - it < 2_000 } != true)
                            status(ViewerPhase.RECONNECTING, if (serverFull) "Сервер заполнен. Ожидаем свободное место…" else "Восстанавливаем соединение…")
                    }
                    is Event.Ice -> if (event.generation == generation && authenticated) {
                        send(Signal(SignalType.ICE, generation = generation, candidate = event.candidate.candidate,
                            mid = event.candidate.mid, line = event.candidate.line))
                    }
                    is Event.Message -> {
                        if (event.epoch != epoch) continue@loop
                        val m = event.message
                        when (m.type) {
                            SignalType.ERROR -> {
                                if (m.code == "RATE_LIMIT") {
                                    admissionRetryAt = now + 60_000
                                    status(ViewerPhase.RECONNECTING, m.message)
                                } else if (m.code == "FULL") {
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
                                    stage = 2; deadline = platform.now() + 12_000
                                    status(ViewerPhase.RECONNECTING, "Повторное согласование видео…")
                                }
                            }
                            SignalType.ICE -> if (m.generation == generation) {
                                peer?.addIce(ViewerIce(m.candidate, m.mid, m.line))
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
                        if (current?.state == MediaState.DISCONNECTED) {
                            if (disconnectedSince == null) disconnectedSince = now
                        } else disconnectedSince = null
                        if (socket == null && !opening && networkAvailable && now >= maxOf(nextAttempt, admissionRetryAt)) {
                            opening = true; epoch++; val openingEpoch = epoch
                            val endpoint = servers.value.firstOrNull { it.id == resolvedServerId } ?: initial
                            socketJob?.cancel()
                            socketJob = launch {
                                var connected: ViewerSocket? = null
                                try {
                                    connected = withTimeout(7_000) { transport.open(endpoint) }
                                    events.send(Event.Open(openingEpoch, connected))
                                    connected.messages.collect { events.send(Event.Message(openingEpoch, it)) }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    if (e is UntrustedSignalingServer) events.send(Event.Untrusted(openingEpoch))
                                    else platform.log("WebSocket open/read failed: ${e.javaClass.simpleName}")
                                }
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
                                current.state == MediaState.FAILED,
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
                    socket?.send(Signal(SignalType.LEAVE, generation = generation))
                    socket?.flush()
                    delay(50)
                } }
                // A failing adapter must not prevent release of the remaining resources.
                val releases: List<suspend () -> Unit> = listOf(
                    { ticks.cancel(); networks.cancel() },
                    { socketJob?.cancelAndJoin() },
                    { iceJob?.cancelAndJoin() },
                    { mutableVideo.value = null; peer?.close() },
                    { transport?.close() },
                    { runtime?.close() },
                )
                for (release in releases) try { release() }
                catch (e: Exception) { platform.log("Release failed: ${e.javaClass.simpleName}") }
                events.close()
                registerStop(null)
            }
        }
        terminal
    }
}
