package dev.localstream

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.*
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.localstream.client.api.ServerEndpoint
import dev.localstream.client.api.ViewerPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.PrintWriter

/** Explicitly opted-in, six-physical-device test. The desktop runner coordinates all roles. */
class FiveDeviceSoakTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as StreamApplication
    private val args get() = InstrumentationRegistry.getArguments()

    private fun settings(): Pair<String, Long> {
        assumeTrue("Use tools/run_five_device_soak.py", args.getString("soakOptIn") == "true")
        check(Build.HARDWARE !in listOf("ranchu", "goldfish")) { "This test requires physical devices" }
        val id = requireNotNull(args.getString("runId")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val seconds = requireNotNull(args.getString("durationSeconds")).toLong().also { require(it in 60..7200) }
        return id to seconds * 1_000
    }

    private fun file(id: String, name: String) = File(context.filesDir, "load/$id-$name").also { it.parentFile!!.mkdirs() }

    private fun openActivity(mode: String): MainViewModel {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n dev.localstream/dev.localstream.MainActivity")).use { it.readBytes() }
        instrumentation.waitForIdleSync()
        var model: MainViewModel? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().first()
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            model!!.mode = mode
        }
        return requireNotNull(model)
    }

    @Test fun host(): Unit = runBlocking {
        val (id, duration) = settings()
        val model = openActivity("server")
        val ready = file(id, "ready.json")
        ready.delete()
        require(!graph.host.state.value.running) { "Stop the existing broadcast before a soak run" }
        var ownsService = false
        try {
            require(!graph.host.state.value.running) { "Stop the existing broadcast before a soak run" }
            val backend = args.getString("backend") ?: "camera2"
            require(backend in listOf("camera2", "ndk"))
            ownsService = true
            context.startForegroundService(Intent(context, StreamingService::class.java).putExtra("backend", backend))
            val state = withTimeout(30_000) { graph.host.state.first { it.running || it.error != null } }
            check(state.running) { "Host failed to start" }
            // Debug test coordination only: private app storage, read using USB run-as.
            // Credentials are never written to CSV or logcat and are deleted after bootstrap.
            ready.writeText(JSONObject().put("accessCode", state.accessCode).put("fingerprint", state.fingerprint).toString())
            withTimeout(150_000) { graph.host.state.first { it.viewers == 5 } }
            withTimeout(60_000) {
                while (true) {
                    val peers = graph.host.diagnostics()
                    if (peers.size == 5 && peers.all { it.connection == "CONNECTED" && (it.framesEncoded ?: 0) > 0 }) break
                    delay(500)
                }
            }
            val started = SystemClock.elapsedRealtime()
            file(id, "started.json").writeText("{}")
            file(id, "host.csv").printWriter().use { csv ->
                csv.println("epochMs,elapsedMs,viewers,peer,generation,connection,bytesSent,framesEncoded,encodeSeconds,encoderFps,qualityLimit,uidTxBytes,uidRxBytes,thermalStatus,batteryTempC,currentUa,chargeUah,energyNwh,plugged")
                while (SystemClock.elapsedRealtime() - started < duration) {
                    val peers = graph.host.diagnostics()
                    check(graph.host.state.value.running) { "Host stopped during the run" }
                    val epochMs = System.currentTimeMillis()
                    val elapsedMs = SystemClock.elapsedRealtime()
                    val power = powerSample()
                    for (peer in peers) {
                        csv.row(epochMs, elapsedMs, peers.size, peer.viewerId, peer.generation,
                            peer.connection, peer.bytesSent, peer.framesEncoded, peer.encodeSeconds, peer.framesPerSecond, peer.qualityLimit,
                            TrafficStats.getUidTxBytes(Process.myUid()).supported(), TrafficStats.getUidRxBytes(Process.myUid()).supported(),
                            *power.toTypedArray())
                    }
                    // Preserve a zero-viewer outage instead of silently omitting it from analysis.
                    if (peers.isEmpty()) csv.row(epochMs, elapsedMs, 0,
                        "", "", "NONE", null, null, null, null, "", null, null, *powerSample().toTypedArray())
                    csv.flush()
                    delay(2_000)
                }
            }
            assertEquals("All five viewer slots must still be present", 5, graph.host.state.value.viewers)
        } finally {
            ready.delete()
            file(id, "started.json").delete()
            if (ownsService) {
                context.startService(Intent(context, StreamingService::class.java).setAction("stop"))
                withContext(NonCancellable) { withTimeout(15_000) { graph.host.state.first { !it.running } } }
            }
            instrumentation.runOnMainSync { model.mode = "home" }
        }
    }

    @Test fun viewer(): Unit = runBlocking {
        val (id, duration) = settings()
        val model = openActivity("client")
        val viewer = model.viewer
        val endpoint = ServerEndpoint("soak-server", "Soak server", requireNotNull(args.getString("serverHost")),
            fingerprint = requireNotNull(args.getString("serverFingerprint")))
        val minimumFps = (args.getString("minimumFps") ?: "15").toInt().also { require(it in 1..30) }
        try {
            viewer.connect(endpoint, requireNotNull(args.getString("accessCode")))
            withTimeout(60_000) { viewer.state.first { it.phase == ViewerPhase.PLAYING && it.frames >= 10 } }
            val started = SystemClock.elapsedRealtime()
            var lastProgress = started
            var previousFrames = viewer.state.value.frames
            var previousSample = started
            var lowFps = 0
            var samples = 0
            var ended = false
            file(id, "viewer.csv").printWriter().use { csv ->
                csv.println("epochMs,elapsedMs,phase,frames,receivedFps,attempt,progressGapMs,thermalStatus,batteryTempC,currentUa,chargeUah,energyNwh,plugged")
                while (SystemClock.elapsedRealtime() - started < duration + 180_000) {
                    delay(2_000)
                    val state = viewer.state.value
                    val now = SystemClock.elapsedRealtime()
                    val progressed = state.phase == ViewerPhase.PLAYING && state.frames != previousFrames && state.frames > 0
                    if (progressed) lastProgress = now
                    // Use actual monotonic sample duration, not the UI's nominal
                    // half-second tick. A new generation resets its frame counter.
                    val deltaFrames = if (state.frames >= previousFrames) state.frames - previousFrames else state.frames
                    val fps = if (progressed) deltaFrames * 1000.0 / (now - previousSample) else 0.0
                    csv.row(System.currentTimeMillis(), now, state.phase, state.frames, fps, state.attempt,
                        now - lastProgress, *powerSample().toTypedArray())
                    csv.flush()
                    if (state.phase == ViewerPhase.ENDED) { ended = true; break }
                    check(state.phase != ViewerPhase.ERROR) { "Viewer entered ERROR" }
                    check(now - lastProgress <= 20_000) { "No new received frames for 20 seconds" }
                    if (fps < minimumFps) lowFps++
                    samples++
                    previousFrames = state.frames
                    previousSample = now
                }
            }
            assertTrue("Server must explicitly end the run", ended)
            assertTrue("Broadcast ended too early", SystemClock.elapsedRealtime() - started >= duration - 5_000)
            assertTrue("At least 90% of samples must meet $minimumFps received FPS (including injected outages)",
                samples > 0 && lowFps.toDouble() / samples <= 0.10)
        } finally {
            withContext(NonCancellable) { viewer.disconnect() }
        }
    }

    /** Battery temperature is not CPU temperature; USB charging affects all energy counters. */
    private fun powerSample(): List<Any?> {
        val manager = context.getSystemService(BatteryManager::class.java)
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
        fun counter(property: Int) = manager.getLongProperty(property).takeUnless { it == Long.MIN_VALUE }
        return listOf(context.getSystemService(PowerManager::class.java).currentThermalStatus, temperature,
            counter(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), counter(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            counter(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER), battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
    }

    private fun Long.supported() = takeUnless { it < 0 }
    private fun PrintWriter.row(vararg values: Any?) { println(values.joinToString(",") { it?.toString()?.replace(',', ';')?.replace('\n', ' ') ?: "" }) }
}
