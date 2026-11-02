package dev.localstream.server

import dev.localstream.network.SignalingIdentity
import dev.localstream.network.pinSignalingServer
import io.ktor.websocket.*
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SignalingServerTest {
    @Test fun wssSessionUsesOnlyTlsAndRejectsReplacedIdentity(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val identity = SignalingIdentity()
        val server = scope.signalingServer(identity, 0) {
            for (frame in incoming) if (frame is Frame.Text) send(frame.readText())
        }
        try {
            server.start(false)
            val port = withTimeout(5_000) { server.engine.resolvedConnectors() }.single().port
            for (pin in listOf(identity.fingerprint, "00".repeat(32))) {
                val results = LinkedBlockingQueue<String>()
                val client = OkHttpClient.Builder().pinSignalingServer(pin).build()
                val socket = client.newWebSocket(Request.Builder().url("https://127.0.0.1:$port/session").build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("join-secret") }
                    override fun onMessage(webSocket: WebSocket, text: String) { results.add(text) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { results.add("rejected") }
                })
                try { assertEquals(if (pin == identity.fingerprint) "join-secret" else "rejected", results.poll(10, TimeUnit.SECONDS)) }
                finally { socket.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
            }
        } finally { server.stop(0, 1_000); scope.cancel() }
    }
}
