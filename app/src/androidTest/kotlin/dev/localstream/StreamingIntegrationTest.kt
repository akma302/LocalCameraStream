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
import dev.localstream.camera.api.*
import android.view.Surface

@RunWith(AndroidJUnit4::class)
class StreamingIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = instrumentation.targetContext.applicationContext as StreamApplication

    @Before fun openActivity() {
        val command = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n dev.localstream/dev.localstream.MainActivity")
        ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
        instrumentation.waitForIdleSync()
    }

    @Test fun camera2ProducesVideoAndWebSocketOutagePreservesMedia() = runBlocking {
        exercise("camera2", true)
    }
    @Test fun ndkCameraProducesVideoAndReleasesForNextSession() = runBlocking {
        exercise("ndk", false)
        exercise("ndk", false)
    }

    @Test fun discoveryAndPauseResumeReleaseViewerSlot() = runBlocking {
        val viewer = DefaultViewerSession(graph)
        try {
            viewer.startDiscovery()
            graph.host.start(graph.camera("camera2"))
            val endpoint = withTimeout(20_000) { viewer.servers.first { it.isNotEmpty() } }.first()
            assertEquals(8765, endpoint.port)
            viewer.connect(endpoint, graph.host.state.value.accessCode)
            withTimeout(30_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 10 } }
            viewer.setVisible(false)
            assertEquals(ViewerPhase.PAUSED, viewer.state.value.phase)
            withTimeout(5_000) { graph.host.state.first { it.viewers == 0 } }
            delay(2_000)
            assertEquals(0, graph.host.state.value.viewers)
            viewer.setVisible(true)
            withTimeout(30_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 10 } }
            assertEquals(1, graph.host.state.value.viewers)
        } finally { viewer.close(); graph.host.stop() }
    }

    @Test fun fiveViewerSessionsAndSixthWaitsForCapacity() = runBlocking {
        val viewers = List(6) { DefaultViewerSession(graph) }
        try {
            graph.host.start(graph.camera("camera2"))
            val endpoint = ServerEndpoint("capacity-test", "Capacity", "127.0.0.1")
            viewers.take(5).forEach { viewer ->
                viewer.connect(endpoint, graph.host.state.value.accessCode)
                withTimeout(35_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 3 } }
            }
            assertEquals(5, graph.host.state.value.viewers)
            val counts = viewers.take(5).map { it.state.value.frames }
            delay(5_000)
            viewers.take(5).forEachIndexed { index, viewer ->
                assertTrue("All five viewers must continue receiving frames", viewer.state.value.frames > counts[index])
            }
            viewers.last().connect(endpoint, graph.host.state.value.accessCode)
            withTimeout(10_000) { viewers.last().state.first { it.message.contains("заполнен") } }
            assertEquals(5, graph.host.state.value.viewers)
            viewers.first().disconnect()
            withTimeout(30_000) { viewers.last().state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 3 } }
            assertEquals(5, graph.host.state.value.viewers)
        } finally { viewers.forEach { it.close() }; graph.host.stop() }
    }

    @Test fun stalledFramesTriggerRecoveryAndResumeAutomatically() = runBlocking {
        val viewer = DefaultViewerSession(graph)
        val camera = PausableCamera(graph.camera("camera2"))
        try {
            graph.host.start(camera)
            viewer.connect(ServerEndpoint("stall-test", "Stall", "127.0.0.1"), graph.host.state.value.accessCode)
            withTimeout(30_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 10 } }
            camera.pause()
            withTimeout(15_000) { viewer.state.first { it.phase == ViewerPhase.RECONNECTING } }
            camera.resume()
            withTimeout(30_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING } }
            assertEquals(1, graph.host.state.value.viewers)
        } finally { viewer.close(); graph.host.stop() }
    }

    /** Fault injection stops frames while leaving signaling and transport alive. */
    private class PausableCamera(private val delegate: CameraApi) : CameraApi by delegate {
        private lateinit var config: CaptureConfig
        private lateinit var output: Surface
        private var active: CameraSession? = null
        override suspend fun startCapture(config: CaptureConfig, output: Surface): CameraSession {
            this.config = config; this.output = output
            val first = delegate.startCapture(config, output).also { active = it }
            return object : CameraSession {
                override val state = first.state
                override suspend fun close() { active?.close(); active = null }
            }
        }
        suspend fun pause() { active?.close(); active = null }
        suspend fun resume() { active = delegate.startCapture(config, output) }
    }

    @Test fun foregroundServiceKeepsCameraStreamingInBackground() = runBlocking {
        val viewer = DefaultViewerSession(graph)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            instrumentation.targetContext.startForegroundService(Intent(instrumentation.targetContext, StreamingService::class.java).putExtra("backend", "camera2"))
            withTimeout(15_000) { graph.host.state.first { it.running } }
            viewer.connect(ServerEndpoint("service-test", "Service", "127.0.0.1"), graph.host.state.value.accessCode)
            val playing = withTimeout(30_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 10 } }
            shell("input keyevent KEYCODE_HOME")
            delay(5_000)
            assertTrue(graph.host.state.value.running)
            assertEquals(ViewerPhase.PLAYING, viewer.state.value.phase)
            assertTrue(viewer.state.value.frames > playing.frames + 10)
        } finally {
            viewer.close()
            instrumentation.targetContext.startService(Intent(instrumentation.targetContext, StreamingService::class.java).setAction("stop"))
            withTimeout(10_000) { graph.host.state.first { !it.running } }
            shell("am start -W -n dev.localstream/dev.localstream.MainActivity")
        }
    }

    @Test fun viewerSurvivesActivityRotationWithSameViewModel() = runBlocking {
        fun activity(): MainActivity {
            var result: MainActivity? = null
            instrumentation.runOnMainSync {
                result = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
            }
            return requireNotNull(result)
        }
        val original = activity()
        val previousOrientation = original.requestedOrientation
        var model: MainViewModel? = null
        instrumentation.runOnMainSync {
            model = androidx.lifecycle.ViewModelProvider(original)[MainViewModel::class.java]
            model!!.mode = "client"
        }
        val viewer = model!!.viewer
        try {
            graph.host.start(graph.camera("camera2"))
            viewer.connect(ServerEndpoint("rotation", "Rotation", "127.0.0.1"), graph.host.state.value.accessCode)
            val before = withTimeout(30_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 10 } }
            instrumentation.runOnMainSync { original.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            delay(3_000)
            val rotated = activity()
            instrumentation.runOnMainSync { assertSame(model, androidx.lifecycle.ViewModelProvider(rotated)[MainViewModel::class.java]) }
            withTimeout(15_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames > before.frames + 10 } }
            assertEquals(1, graph.host.state.value.viewers)
        } finally {
            viewer.disconnect(); graph.host.stop()
            val current = activity()
            instrumentation.runOnMainSync { current.requestedOrientation = previousOrientation }
        }
    }

    @Test fun wifiOutageRestoresVideoWithoutReselectingServer() = runBlocking {
        org.junit.Assume.assumeTrue("Physical Wi-Fi fault tests require explicit opt-in",
            android.os.Build.MODEL.contains("SDK", ignoreCase = true) ||
                InstrumentationRegistry.getArguments().getString("physicalWifiTest") == "true")
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
            android.util.Log.i("DeviceTest", "Starting host $backend")
            graph.host.start(graph.camera(backend))
            android.util.Log.i("DeviceTest", "Host ready $backend")
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
