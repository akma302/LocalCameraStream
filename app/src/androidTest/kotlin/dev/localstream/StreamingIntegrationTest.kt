package dev.localstream

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localstream.client.api.ServerEndpoint
import dev.localstream.client.api.ViewerPhase
import dev.localstream.client.impl.DefaultViewerSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class StreamingIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = instrumentation.targetContext.applicationContext as StreamApplication

    @Before fun openActivity() {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun camera2ProducesVideoAndWebSocketOutagePreservesMedia() = runBlocking {
        exercise("camera2", true)
    }
    @Test fun ndkCameraProducesVideoAndReleasesForNextSession() = runBlocking {
        exercise("ndk", false)
        exercise("ndk", false)
    }

    @Test fun wifiOutageRestoresVideoWithoutReselectingServer() = runBlocking {
        org.junit.Assume.assumeTrue("Network fault test is restricted to emulators", android.os.Build.MODEL.contains("SDK", ignoreCase = true))
        val viewer = DefaultViewerSession(graph)
        fun wifi(enabled: Boolean) {
            val fd = instrumentation.uiAutomation.executeShellCommand("svc wifi ${if (enabled) "enable" else "disable"}")
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        try {
            graph.host.start(graph.camera("camera2"))
            viewer.connect(ServerEndpoint("test-wifi", "Wi-Fi recovery test", "127.0.0.1"), graph.host.state.value.accessCode)
            val before = withTimeout(30_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 15 } }
            wifi(false)
            withTimeout(10_000) { viewer.state.first { it.phase == ViewerPhase.WAITING_FOR_NETWORK } }
            delay(4_000)
            wifi(true)
            withTimeout(35_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames > before.frames + 15 } }
            assertEquals(1, graph.host.state.value.viewers)
            viewer.disconnect()
        } finally { wifi(true); viewer.close(); graph.host.stop() }
    }

    private suspend fun exercise(backend: String, disrupt: Boolean) {
        val viewer = DefaultViewerSession(graph)
        val proxy = ControlProxy()
        try {
            graph.host.start(graph.camera(backend))
            assertTrue(graph.host.state.value.running)
            viewer.connect(ServerEndpoint("test", "Local integration test", "127.0.0.1", proxy.port), graph.host.state.value.accessCode)
            val playing = withTimeoutOrNull(30_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 10 } }
                ?: error("No video: ${viewer.state.value}; host=${graph.host.state.value}")
            assertTrue(playing.frames >= 10)
            assertEquals(1, graph.host.state.value.viewers)
            if (disrupt) {
                proxy.allowConnections = false
                proxy.cutConnections()
                delay(6_000)
                assertEquals(ViewerPhase.PLAYING, viewer.state.value.phase)
                assertTrue("Video must continue while control is unavailable", viewer.state.value.frames > playing.frames + 10)
                proxy.allowConnections = true
                withTimeout(20_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && !it.message.contains("управление") } }
                assertEquals("Resuming the same viewer must not allocate a second slot", 1, graph.host.state.value.viewers)
            }
            viewer.disconnect()
            withTimeout(5_000) { graph.host.state.first { it.viewers == 0 } }
            delay(1_000)
            assertEquals(ViewerPhase.IDLE, viewer.state.value.phase)
        } finally {
            viewer.close(); proxy.close(); graph.host.stop()
        }
    }

    /** Only signaling goes through this proxy; WebRTC media uses its direct ICE path. */
    private class ControlProxy : AutoCloseable {
        private val server = ServerSocket(0)
        private val pool = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        @Volatile var allowConnections = true
        @Volatile private var closed = false
        val port get() = server.localPort
        init {
            pool.execute {
                while (!closed) {
                    val incoming = try { server.accept() } catch (_: Exception) { break }
                    if (!allowConnections) { incoming.close(); continue }
                    val outgoing = Socket("127.0.0.1", 8765)
                    sockets += incoming; sockets += outgoing
                    fun forward(from: Socket, to: Socket) = pool.execute {
                        try { from.getInputStream().copyTo(to.getOutputStream()) }
                        catch (_: Exception) { }
                        finally { runCatching { from.close() }; runCatching { to.close() }; sockets.remove(from); sockets.remove(to) }
                    }
                    forward(incoming, outgoing); forward(outgoing, incoming)
                }
            }
        }
        fun cutConnections() { sockets.toList().forEach { runCatching { it.close() } } }
        override fun close() { closed = true; server.close(); cutConnections(); pool.shutdownNow() }
    }
}
