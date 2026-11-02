package dev.localstream.client.impl

import dev.localstream.client.api.ServerEndpoint
import dev.localstream.protocol.Signal
import dev.localstream.video.api.VideoOutput
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

/** Boundaries used by the production event loop and deterministic JVM scenarios. */
internal interface ViewerPlatform {
    fun now(): Long
    fun jitter(): Double
    fun log(message: String)
    fun discover(): Flow<List<ServerEndpoint>>
    fun networks(): Flow<Boolean>
    fun createRuntime(): ViewerRuntime
    fun createTransport(initial: ServerEndpoint): ViewerTransport
}

internal interface ViewerTransport {
    suspend fun open(endpoint: ServerEndpoint): ViewerSocket
    suspend fun close()
}

internal interface ViewerSocket {
    val messages: Flow<Signal>
    suspend fun send(signal: Signal)
    suspend fun flush()
    suspend fun close()
}

internal data class ViewerIce(val candidate: String, val mid: String, val line: Int)
internal enum class MediaState { CONNECTED, DISCONNECTED, FAILED, OTHER }

internal interface ViewerRuntime {
    fun createPeer(): ViewerPeer
    suspend fun close()
}

internal interface ViewerPeer {
    val output: VideoOutput
    val receivedFrames: Long
    val lastFrameAt: Long
    val state: MediaState
    val candidates: ReceiveChannel<ViewerIce>
    suspend fun answer(sdp: String): String
    fun addIce(ice: ViewerIce)
    fun close()
}

internal class UntrustedSignalingServer(cause: Throwable) : Exception("Server identity verification failed", cause)
