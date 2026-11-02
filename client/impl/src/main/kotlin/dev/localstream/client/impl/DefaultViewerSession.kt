package dev.localstream.client.impl

import android.content.Context
import dev.localstream.client.api.*
import dev.localstream.video.api.VideoOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DefaultViewerSession internal constructor(
    private val platform: ViewerPlatform,
    dispatcher: CoroutineDispatcher,
) : ViewerSession {
    constructor(context: Context) : this(AndroidViewerPlatform(context.applicationContext), Dispatchers.IO)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
                    platform.discover().collect { mutableServers.value = it }
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

    private suspend fun watch(initial: ServerEndpoint, accessCode: String) {
        val terminal = ViewerWorker(platform, mutableServers, mutableState, mutableVideo, clientId) { requestStop = it }
            .run(initial, accessCode)
        if (terminal) desired = null
    }
}
