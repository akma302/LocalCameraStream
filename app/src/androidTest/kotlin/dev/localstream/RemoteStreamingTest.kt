package dev.localstream

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.localstream.client.api.ServerEndpoint
import dev.localstream.client.api.ViewerPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Requires a separate, already broadcasting physical server; never starts a local camera. */
class RemoteStreamingTest {
    @Test fun physicalServerToEmulatorClient(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue("Network fault injection requires an emulator",
            android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        val args = InstrumentationRegistry.getArguments()
        val host = requireNotNull(args.getString("serverHost"))
        val code = requireNotNull(args.getString("accessCode"))
        if (args.getString("webrtcDiagnostics") == "true") {
            org.webrtc.PeerConnectionFactory.initialize(org.webrtc.PeerConnectionFactory.InitializationOptions
                .builder(instrumentation.targetContext).createInitializationOptions())
            org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO)
        }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("am start -W -n dev.localstream/dev.localstream.MainActivity")
        var model: MainViewModel? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().first()
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            model!!.mode = "client"
        }
        val viewer = model!!.viewer
        try {
            viewer.connect(ServerEndpoint("physical-xiaomi", "Xiaomi", host), code)
            withTimeoutOrNull(60_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 15 } }
                ?: error("Remote video did not start: ${viewer.state.value}")
            repeat(5) {
                val before = viewer.state.value.frames
                delay(2_000)
                assertEquals(ViewerPhase.PLAYING, viewer.state.value.phase)
                assertTrue("Frames must continue from the physical server", viewer.state.value.frames > before)
            }
            android.util.Log.i("RemoteStreamTest", "Initial video: frames=${viewer.state.value.frames}, fps=${viewer.state.value.fps}")
            shell("svc wifi disable")
            // An emulator can retain an Ethernet network while Wi-Fi is off. Verify
            // the actual media interruption, without assuming a specific UI phase.
            delay(1_000)
            val framesDuringOutage = viewer.state.value.frames
            delay(3_000)
            assertEquals("Video frames must stop while Wi-Fi is disabled", framesDuringOutage, viewer.state.value.frames)
            android.util.Log.i("RemoteStreamTest", "Network outage: phase=${viewer.state.value.phase}, frames=${viewer.state.value.frames}")
            val recoveryStarted = SystemClock.elapsedRealtime()
            shell("svc wifi enable")
            withTimeout(60_000) { viewer.state.first {
                it.phase == ViewerPhase.PLAYING && it.frames > 0 && it.frames != framesDuringOutage
            } }
            android.util.Log.i("RemoteStreamTest", "Recovery after Wi-Fi enable: ${SystemClock.elapsedRealtime() - recoveryStarted} ms")
            repeat(5) {
                val before = viewer.state.value.frames
                delay(2_000)
                assertEquals(ViewerPhase.PLAYING, viewer.state.value.phase)
                assertTrue("Frames must continue after network recovery", viewer.state.value.frames > before)
            }
            android.util.Log.i("RemoteStreamTest", "Recovered video: frames=${viewer.state.value.frames}, fps=${viewer.state.value.fps}")
        } finally { shell("svc wifi enable"); viewer.disconnect() }
    }
}
