package dev.localstream.client.impl

import android.content.Context
import android.view.View
import dev.localstream.client.api.*
import dev.localstream.protocol.*
import dev.localstream.video.api.VideoOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerRecoveryTest {
    private val endpoint = ServerEndpoint("host", "Camera", "192.168.0.2")

    @Test fun signalingLossKeepsHealthyMediaAndResumesWithToken(): Unit = runTest {
        val platform = FakePlatform(this)
        val session = DefaultViewerSession(platform, StandardTestDispatcher(testScheduler))
        try {
            session.connect(endpoint, "123456"); tick()
            val peer = platform.peer
            platform.frames(); tick()
            val first = platform.sockets.single()
            first.incoming.close(); runCurrent()
            assertFalse(peer.closed)
            platform.frames(); tick()
            assertEquals(ViewerPhase.PLAYING, session.state.value.phase)
            assertSame(peer.output, session.video.value)
            assertEquals(2, platform.sockets.size)
            val join = platform.sockets.last().sent.first { it.type == SignalType.JOIN }
            assertEquals("resume-token", join.token)
            assertEquals("g1", join.generation)
            assertEquals(1, platform.runtimes.single().peers.size)
        } finally { session.close() }
    }

    @Test fun networkReturnBypassesBackoffAndUsesNewDiscoveredAddress(): Unit = runTest {
        val platform = FakePlatform(this)
        val session = DefaultViewerSession(platform, StandardTestDispatcher(testScheduler))
        try {
            session.startDiscovery(); session.connect(endpoint, "123456"); tick()
            repeat(4) { platform.sockets.last().incoming.close(); runCurrent(); advanceTimeBy(10_000); runCurrent() }
            platform.network.value = false; runCurrent()
            platform.sockets.last().incoming.close(); runCurrent()
            val before = platform.opened.size
            advanceTimeBy(20_000); runCurrent()
            assertEquals(before, platform.opened.size)
            assertEquals(ViewerPhase.WAITING_FOR_NETWORK, session.state.value.phase)
            platform.discovered.value = listOf(endpoint.copy(host = "192.168.0.99")); runCurrent()
            platform.network.value = true; tick()
            assertEquals(before + 1, platform.opened.size)
            assertEquals("192.168.0.99", platform.opened.last().host)
        } finally { session.close() }
    }

    @Test fun stalledMediaRestartsIceThenRecreatesAndProgressStopsEscalation(): Unit = runTest {
        val platform = FakePlatform(this)
        val session = DefaultViewerSession(platform, StandardTestDispatcher(testScheduler))
        try {
            session.connect(endpoint, "123456"); tick()
            val socket = platform.sockets.single()
            advanceTimeBy(4_500); runCurrent()
            assertEquals(1, socket.sent.count { it.type == SignalType.RESTART })
            assertEquals(0, socket.sent.count { it.type == SignalType.RECREATE })
            advanceTimeBy(10_000); runCurrent()
            assertEquals(1, socket.sent.count { it.type == SignalType.RECREATE })
            repeat(30) { platform.frames(); tick() }
            assertEquals(ViewerPhase.PLAYING, session.state.value.phase)
            assertEquals(1, socket.sent.count { it.type == SignalType.RECREATE })
        } finally { session.close() }
    }

    @Test fun failedPeerDoesNotRestartWithoutNetwork(): Unit = runTest {
        val platform = FakePlatform(this)
        val session = DefaultViewerSession(platform, StandardTestDispatcher(testScheduler))
        try {
            session.connect(endpoint, "123456"); tick()
            platform.network.value = false
            platform.peer.state = MediaState.FAILED
            advanceTimeBy(20_000); runCurrent()
            assertTrue(platform.sockets.single().sent.none { it.type == SignalType.RESTART || it.type == SignalType.RECREATE })
            platform.network.value = true; tick()
            assertEquals(1, platform.sockets.single().sent.count { it.type == SignalType.RESTART })
        } finally { session.close() }
    }

    @Test fun staleSocketEventsAndOldGenerationIceCannotChangeCurrentSession(): Unit = runTest {
        val platform = FakePlatform(this)
        val state = MutableStateFlow(ViewerState())
        val video = MutableStateFlow<VideoOutput?>(null)
        val events = Channel<ViewerWorker.Event>(256)
        val worker = ViewerWorker(platform, platform.discovered, state, video, "client", events) {}
        val job = launch { worker.run(endpoint, "123456") }
        try {
            tick(); platform.frames(); tick()
            val staleSocket = FakeSocket()
            events.send(ViewerWorker.Event.Message(0, Signal(SignalType.ENDED)))
            events.send(ViewerWorker.Event.Closed(0))
            events.send(ViewerWorker.Event.Open(0, staleSocket))
            events.send(ViewerWorker.Event.Ice("old", ViewerIce("old-candidate", "0", 0)))
            events.send(ViewerWorker.Event.Message(1, Signal(SignalType.ICE, generation = "old", candidate = "old")))
            runCurrent()
            assertEquals(ViewerPhase.PLAYING, state.value.phase)
            assertTrue(staleSocket.closed)
            assertFalse(platform.peer.closed)
            assertTrue(platform.peer.remoteIce.isEmpty())
            assertTrue(platform.sockets.single().sent.none { it.candidate == "old-candidate" })
            events.send(ViewerWorker.Event.Message(1, Signal(SignalType.WELCOME, generation = "g2", token = "new")))
            runCurrent()
            events.send(ViewerWorker.Event.Ice("g1", ViewerIce("late", "0", 0)))
            events.send(ViewerWorker.Event.Ice("g2", ViewerIce("fresh", "0", 0)))
            runCurrent()
            assertEquals(2, platform.runtimes.single().peers.size)
            assertTrue(platform.runtimes.single().peers.first().closed)
            assertTrue(platform.sockets.single().sent.none { it.candidate == "late" })
            assertTrue(platform.sockets.single().sent.any { it.candidate == "fresh" })
        } finally { job.cancel(); runCurrent(); job.join() }
    }

    @Test fun pauseReleasesEverythingAndResumeCreatesFreshSession(): Unit = runTest {
        val platform = FakePlatform(this)
        val session = DefaultViewerSession(platform, StandardTestDispatcher(testScheduler))
        try {
            session.connect(endpoint, "123456"); tick()
            val runtime = platform.runtimes.single()
            val socket = platform.sockets.single()
            session.setVisible(false)
            assertEquals(ViewerPhase.PAUSED, session.state.value.phase)
            assertTrue(runtime.closed)
            assertTrue(runtime.peers.single().closed)
            assertTrue(socket.sent.any { it.type == SignalType.LEAVE })
            assertNull(session.video.value)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, platform.opened.size)
            session.setVisible(true); tick()
            assertEquals(2, platform.runtimes.size)
            session.disconnect()
            session.setVisible(false); session.setVisible(true); tick()
            assertEquals(2, platform.runtimes.size)
            assertEquals(ViewerPhase.IDLE, session.state.value.phase)
        } finally { session.close() }
    }

    @Test fun fullServerRetriesButAuthAndEndedStopFutureAttempts(): Unit = runTest {
        for (code in listOf("FULL", "AUTH", "ENDED")) {
            val platform = FakePlatform(this)
            val session = DefaultViewerSession(platform, StandardTestDispatcher(testScheduler))
            try {
                session.connect(endpoint, "123456"); tick()
                platform.sockets.single().incoming.send(Signal(SignalType.ERROR, code = code, message = code))
                runCurrent()
                platform.sockets.single().incoming.close()
                advanceTimeBy(1_000); runCurrent()
                assertEquals(if (code == "FULL") 2 else 1, platform.opened.size)
                if (code != "FULL") {
                    session.setVisible(false); session.setVisible(true); tick()
                    assertEquals(1, platform.opened.size)
                }
            } finally { session.close() }
        }
    }

    @Test fun cancellationDuringCleanupStillClosesEveryResource(): Unit = runTest {
        val platform = FakePlatform(this)
        val worker = ViewerWorker(platform, platform.discovered, MutableStateFlow(ViewerState()), MutableStateFlow(null), "client") {}
        val job = launch { worker.run(endpoint, "123456") }
        tick()
        val runtime = platform.runtimes.single()
        runtime.closeGate = CompletableDeferred()
        platform.failTransportClose = true
        try {
            job.cancel(); runCurrent(); advanceTimeBy(100); runCurrent()
            assertTrue(platform.transports.single().closed)
            assertTrue(platform.peer.closed)
            assertFalse(runtime.closed)
            job.cancel(); runCurrent()
            runtime.closeGate!!.complete(Unit); runCurrent(); job.join()
            assertTrue(runtime.closed)
            assertTrue(platform.logs.any { it.contains("Release failed") })
            val opened = platform.opened.size
            advanceTimeBy(60_000); runCurrent()
            assertEquals(opened, platform.opened.size)
        } finally {
            runtime.closeGate!!.complete(Unit)
            job.cancel(); runCurrent(); job.join()
        }
    }

    @Test fun transportInitializationFailureReleasesAlreadyCreatedRuntime(): Unit = runTest {
        val platform = FakePlatform(this).apply { failTransportInit = true }
        val session = DefaultViewerSession(platform, StandardTestDispatcher(testScheduler))
        try {
            session.connect(endpoint, "123456"); tick()
            assertEquals(ViewerPhase.ERROR, session.state.value.phase)
            assertTrue(platform.runtimes.single().closed)
        } finally { session.close() }
    }

    private fun TestScope.tick() { runCurrent(); advanceTimeBy(500); runCurrent() }

    private class FakePlatform(private val test: TestScope) : ViewerPlatform {
        val network = MutableStateFlow(true)
        val discovered = MutableStateFlow(emptyList<ServerEndpoint>())
        val opened = mutableListOf<ServerEndpoint>()
        val sockets = mutableListOf<FakeSocket>()
        val runtimes = mutableListOf<FakeRuntime>()
        val transports = mutableListOf<FakeTransport>()
        val logs = mutableListOf<String>()
        var failTransportInit = false
        var failTransportClose = false
        val peer get() = runtimes.last().peers.last()
        fun frames() { peer.receivedFrames += 15; peer.lastFrameAt = now() }
        override fun now() = test.testScheduler.currentTime
        override fun jitter() = 0.5
        override fun log(message: String) { logs += message }
        override fun discover() = discovered
        override fun networks() = network
        override fun createRuntime() = FakeRuntime().also { runtimes += it }
        override fun createTransport(initial: ServerEndpoint): ViewerTransport {
            check(!failTransportInit) { "Transport creation failed" }
            return FakeTransport(this).also { transports += it }
        }
    }

    private class FakeTransport(private val platform: FakePlatform) : ViewerTransport {
        var closed = false
        override suspend fun open(endpoint: ServerEndpoint): ViewerSocket {
            platform.opened += endpoint
            return FakeSocket().also {
                platform.sockets += it
                it.incoming.send(Signal(SignalType.WELCOME, generation = "g1", token = "resume-token", serverId = "host", streamId = "stream"))
                if (platform.runtimes.last().peers.isEmpty()) it.incoming.send(Signal(SignalType.OFFER, generation = "g1", sdp = "offer"))
            }
        }
        override suspend fun close() { closed = true; check(!platform.failTransportClose) }
    }

    private class FakeSocket : ViewerSocket {
        val incoming = Channel<Signal>(Channel.UNLIMITED)
        val sent = mutableListOf<Signal>()
        var closed = false
        override val messages = incoming.receiveAsFlow()
        override suspend fun send(signal: Signal) { sent += signal }
        override suspend fun flush() {}
        override suspend fun close() { closed = true; incoming.close() }
    }

    private class FakeRuntime : ViewerRuntime {
        val peers = mutableListOf<FakePeer>()
        var closed = false
        var closeGate: CompletableDeferred<Unit>? = null
        override fun createPeer() = FakePeer().also { peers += it }
        override suspend fun close() { closeGate?.await(); closed = true }
    }

    private class FakePeer : ViewerPeer {
        override val output = object : VideoOutput {
            override fun createView(context: Context): View = error("Renderer is not used by the state machine")
            override fun releaseView(view: View) {}
        }
        override var receivedFrames = 0L
        override var lastFrameAt = 0L
        override var state = MediaState.CONNECTED
        override val candidates = Channel<ViewerIce>(Channel.UNLIMITED)
        val remoteIce = mutableListOf<ViewerIce>()
        var closed = false
        override suspend fun answer(sdp: String) = "answer"
        override fun addIce(ice: ViewerIce) { remoteIce += ice }
        override fun close() { closed = true; candidates.close() }
    }
}
