package dev.localstream.client.api

import dev.localstream.video.api.VideoOutput
import kotlinx.coroutines.flow.StateFlow

data class ServerEndpoint(val id: String, val name: String, val host: String, val port: Int = 8765, val fingerprint: String = "")
enum class ViewerPhase { IDLE, CONNECTING, NEGOTIATING, WAITING_FOR_VIDEO, PLAYING, RECONNECTING, WAITING_FOR_NETWORK, PAUSED, ENDED, ERROR }
data class ViewerState(val phase: ViewerPhase = ViewerPhase.IDLE, val message: String = "Выберите трансляцию",
    val server: ServerEndpoint? = null, val frames: Long = 0, val fps: Int = 0, val attempt: Int = 0)

interface ViewerSession {
    val servers: StateFlow<List<ServerEndpoint>>
    val state: StateFlow<ViewerState>
    val video: StateFlow<VideoOutput?>
    fun startDiscovery()
    fun stopDiscovery()
    suspend fun connect(server: ServerEndpoint, accessCode: String)
    suspend fun disconnect()
    suspend fun setVisible(visible: Boolean)
    suspend fun close()
}
