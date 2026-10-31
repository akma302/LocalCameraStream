package dev.localstream.media

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import android.view.View
import dev.localstream.camera.api.*
import dev.localstream.video.api.VideoOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RtcRuntime(context: Context) : AutoCloseable {
    val egl: EglBase = EglBase.create()
    val factory: PeerConnectionFactory
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply { networkIgnoreMask = 4 /* cellular */ })
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }
    override fun close() { factory.dispose(); egl.release() }
}

class RtcVideoOutput(private val runtime: RtcRuntime) : VideoOutput {
    private var track: VideoTrack? = null
    private val views = mutableSetOf<SurfaceViewRenderer>()
    @Synchronized fun setTrack(next: VideoTrack?) {
        views.forEach { track?.removeSink(it) }
        track = next
        views.forEach { next?.addSink(it) }
    }
    @Synchronized override fun createView(context: Context): View = SurfaceViewRenderer(context).apply {
        init(runtime.egl.eglBaseContext, null)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        setEnableHardwareScaler(true)
        views.add(this); track?.addSink(this)
    }
    @Synchronized override fun releaseView(view: View) {
        val renderer = view as? SurfaceViewRenderer ?: return
        if (views.remove(renderer)) { track?.removeSink(renderer); renderer.release() }
    }
}

/** A single camera producer feeds one shared VideoSource; peers send independently. */
class CameraVideoSource(private val runtime: RtcRuntime, private val api: CameraApi) {
    val source: VideoSource = runtime.factory.createVideoSource(false)
    val track: VideoTrack = runtime.factory.createVideoTrack("camera", source)
    val output = RtcVideoOutput(runtime)
    private val helper = SurfaceTextureHelper.create("CameraTexture", runtime.egl.eglBaseContext)
    private var surface: Surface? = null
    private var capture: CameraSession? = null
    private val disposed = AtomicBoolean()
    suspend fun start(config: CaptureConfig, info: CameraInfo) {
        helper.setTextureSize(config.profile.width, config.profile.height)
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) { }
        val target = Surface(helper.surfaceTexture)
        surface = target
        helper.startListening { frame ->
            val rotated = VideoFrame(frame.buffer, info.sensorOrientation, frame.timestampNs)
            source.capturerObserver.onFrameCaptured(rotated)
        }
        try {
            capture = api.startCapture(config, target)
            source.capturerObserver.onCapturerStarted(true)
            output.setTrack(track)
        } catch (e: Throwable) { close(); throw e }
    }
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (disposed.compareAndSet(false, true)) {
            capture?.close(); helper.stopListening(); source.capturerObserver.onCapturerStopped()
            output.setTrack(null); surface?.release(); helper.dispose(); track.dispose(); source.dispose()
        }
    }
}

class RtcPeer(runtime: RtcRuntime, localTrack: VideoTrack? = null) : AutoCloseable {
    val candidates = Channel<IceCandidate>(128)
    private val mutableState = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    val state = mutableState.asStateFlow()
    val output = RtcVideoOutput(runtime)
    @Volatile var lastFrameAt: Long = 0; private set
    @Volatile var receivedFrames: Long = 0; private set
    private var remoteTrack: VideoTrack? = null
    private val trackLock = Any()
    private val sink = VideoSink { lastFrameAt = SystemClock.elapsedRealtime(); receivedFrames++ }
    private val disposed = AtomicBoolean()
    private var hasRemoteDescription = false
    private val pendingIce = mutableListOf<IceCandidate>()
    val connection: PeerConnection = requireNotNull(runtime.factory.createPeerConnection(
        PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN },
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) { candidates.trySend(candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) { mutableState.value = state }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track() as? VideoTrack ?: return
                synchronized(trackLock) {
                    if (!disposed.get()) { remoteTrack = track; track.addSink(sink); output.setTrack(track) }
                }
            }
        }
    ))
    init {
        if (localTrack != null) {
            val sender = connection.addTrack(localTrack, listOf("local-camera"))
            sender?.parameters?.let { p ->
                p.encodings.forEach { it.maxBitrateBps = 2_000_000; it.maxFramerate = 30 }
                sender.parameters = p
            }
            connection.transceivers.forEach { it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY }
        }
    }
    suspend fun offer(restart: Boolean = false): String {
        if (restart) connection.restartIce()
        val constraints = MediaConstraints().apply { if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        val sdp = createSdp { connection.createOffer(it, constraints) }
        setSdp { connection.setLocalDescription(it, sdp) }
        return sdp.description
    }
    suspend fun answer(offer: String): String {
        setRemote(SessionDescription.Type.OFFER, offer)
        connection.transceivers.forEach { it.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY }
        val sdp = createSdp { connection.createAnswer(it, MediaConstraints()) }
        setSdp { connection.setLocalDescription(it, sdp) }
        return sdp.description
    }
    suspend fun setRemote(type: SessionDescription.Type, sdp: String) {
        setSdp { connection.setRemoteDescription(it, SessionDescription(type, sdp)) }
        hasRemoteDescription = true
        pendingIce.forEach { connection.addIceCandidate(it) }; pendingIce.clear()
    }
    fun addIce(candidate: IceCandidate) {
        if (hasRemoteDescription) connection.addIceCandidate(candidate)
        else if (pendingIce.size < 128) pendingIce.add(candidate)
    }
    override fun close() {
        if (disposed.compareAndSet(false, true)) {
            synchronized(trackLock) { output.setTrack(null); remoteTrack?.removeSink(sink); remoteTrack = null }
            connection.close(); connection.dispose(); candidates.close()
        }
    }
}

private open class SdpCallbacks : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(message: String) {}
    override fun onSetFailure(message: String) {}
}
private suspend fun createSdp(call: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
    call(object : SdpCallbacks() {
        override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
        override fun onCreateFailure(message: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException(message)) }
    })
}
private suspend fun setSdp(call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
    call(object : SdpCallbacks() {
        override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
        override fun onSetFailure(message: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException(message)) }
    })
}
