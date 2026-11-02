package dev.localstream.server

import dev.localstream.network.SignalingIdentity
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.seconds

internal fun CoroutineScope.signalingServer(
    identity: SignalingIdentity,
    port: Int = 8765,
    session: suspend DefaultWebSocketServerSession.() -> Unit,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val config = serverConfig {
        parentCoroutineContext = this@signalingServer.coroutineContext
        module {
            install(WebSockets) { pingPeriod = 3.seconds; timeout = 8.seconds; maxFrameSize = 128 * 1024L }
            routing { webSocket("/session", handler = session) }
        }
    }
    return embeddedServer(Netty, config) {
        enableHttp2 = false
        connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 2
        sslConnector(identity.keyStore, "host", { identity.password }, { identity.password }) {
            host = "0.0.0.0"; this.port = port
        }
    }
}
