package dev.localstream.client.impl

import android.content.Context
import android.os.SystemClock
import dev.localstream.client.api.ServerEndpoint
import dev.localstream.media.RtcPeer
import dev.localstream.media.RtcRuntime
import dev.localstream.network.LocalDiscovery
import dev.localstream.network.LocalNetworkMonitor
import dev.localstream.protocol.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal class AndroidViewerPlatform(private val context: Context) : ViewerPlatform {
    override fun now() = SystemClock.elapsedRealtime()
    override fun jitter() = Random.nextDouble()
    override fun log(message: String) { android.util.Log.i("ViewerSession", message) }
    override fun discover() = LocalDiscovery(context).discover().map { list ->
        list.map { ServerEndpoint(it.id, it.name, it.host, it.port) }
    }
    override fun networks() = LocalNetworkMonitor(context).changes().map { it.available }
    override fun createRuntime(): ViewerRuntime = AndroidViewerRuntime(RtcRuntime(context))
    override fun createTransport(initial: ServerEndpoint): ViewerTransport = KtorViewerTransport()
}

internal class KtorViewerTransport : ViewerTransport {
    private val http = HttpClient(OkHttp) {
        install(WebSockets)
        engine { config { connectTimeout(5, TimeUnit.SECONDS); pingInterval(3, TimeUnit.SECONDS); readTimeout(0, TimeUnit.MILLISECONDS) } }
    }
    override suspend fun open(endpoint: ServerEndpoint): ViewerSocket {
        val session = http.webSocketSession {
            url { protocol = URLProtocol.WS; host = endpoint.host; port = endpoint.port; encodedPath = "/session" }
        }
        return object : ViewerSocket {
            override val messages = flow {
                for (frame in session.incoming) if (frame is Frame.Text) {
                    require(frame.data.size <= 128 * 1024) { "Слишком большое управляющее сообщение" }
                    emit(wireJson.decodeFromString<Signal>(frame.readText()))
                }
            }
            override suspend fun send(signal: Signal) { session.send(wireJson.encodeToString(signal)) }
            override suspend fun flush() { session.flush() }
            override suspend fun close() { session.close() }
        }
    }
    override suspend fun close() { http.close() }
}

private class AndroidViewerRuntime(private val runtime: RtcRuntime) : ViewerRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun createPeer(): ViewerPeer {
        val peer = RtcPeer(runtime)
        val ice = Channel<ViewerIce>(128)
        val forwarding = scope.launch {
            try { for (candidate in peer.candidates) ice.send(ViewerIce(candidate.sdp, candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex)) }
            finally { ice.close() }
        }
        return object : ViewerPeer {
            override val output get() = peer.output
            override val receivedFrames get() = peer.receivedFrames
            override val lastFrameAt get() = peer.lastFrameAt
            override val candidates = ice
            override val state get() = when (peer.state.value) {
                PeerConnection.PeerConnectionState.CONNECTED -> MediaState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> MediaState.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> MediaState.FAILED
                else -> MediaState.OTHER
            }
            override suspend fun answer(sdp: String) = peer.answer(sdp)
            override fun addIce(ice: ViewerIce) { peer.addIce(IceCandidate(ice.mid, ice.line, ice.candidate)) }
            override fun close() { forwarding.cancel(); ice.close(); peer.close() }
        }
    }
    override suspend fun close() { scope.cancel(); runtime.close() }
}
