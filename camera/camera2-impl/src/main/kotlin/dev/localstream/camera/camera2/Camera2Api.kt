package dev.localstream.camera.camera2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import dev.localstream.camera.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class Camera2Api(context: Context) : CameraApi {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val occupied = AtomicBoolean()

    override suspend fun listCameras(): List<CameraInfo> = withContext(Dispatchers.IO) {
        manager.cameraIdList.map { id ->
            val c = manager.getCameraCharacteristics(id)
            CameraInfo(CameraId(id), when (c[CameraCharacteristics.LENS_FACING]) {
                CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK
                else -> LensFacing.EXTERNAL
            }, c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0)
        }
    }

    override suspend fun getCapabilities(cameraId: CameraId): CameraCapabilities = withContext(Dispatchers.IO) {
        val c = manager.getCameraCharacteristics(cameraId.value)
        val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?: throw CameraException(CameraError.UNSUPPORTED, "Нет конфигураций камеры")
        val ranges = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
        CameraCapabilities(map.getOutputSizes(SurfaceTexture::class.java).orEmpty().flatMap { size ->
            val duration = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
            ranges.filter { it.upper <= 30 && (duration == 0L || duration <= 1_000_000_000L / it.upper + 1_000_000L) }
                .map { CaptureProfile(size.width, size.height, it.lower, it.upper) }
        }.distinct())
    }

    @SuppressLint("MissingPermission")
    override suspend fun startCapture(config: CaptureConfig, output: Surface): CameraSession {
      var allocated: Session? = null
      try { return withContext(Dispatchers.IO) {
        if (!occupied.compareAndSet(false, true)) throw CameraException(CameraError.BUSY, "Камера уже используется")
        val session = Session { occupied.set(false) }.also { allocated = it }
        try {
            require(output.isValid) { "Surface недействительна" }
            if (config.profile !in getCapabilities(config.cameraId).profiles)
                throw CameraException(CameraError.UNSUPPORTED, "Профиль не поддерживается камерой")
            val opened = CompletableDeferred<CameraDevice>()
            manager.openCamera(config.cameraId.value, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (session.closed.get()) device.close() else { session.device = device; opened.complete(device) }
                }
                override fun onDisconnected(device: CameraDevice) {
                    val e = CameraException(CameraError.DISCONNECTED, "Камера отключена")
                    session.fail(e); opened.completeExceptionally(e); device.close()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    val code = if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) CameraError.BUSY else CameraError.DEVICE_FAILURE
                    val e = CameraException(code, "Ошибка Camera2: $error")
                    session.fail(e); opened.completeExceptionally(e); device.close()
                }
                override fun onClosed(device: CameraDevice) { session.deviceClosed.complete(Unit) }
            }, session.handler)
            val device = withTimeout(10_000) { opened.await() }
            val configured = CompletableDeferred<CameraCaptureSession>()
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(capture: CameraCaptureSession) {
                    if (session.closed.get()) capture.close() else { session.capture = capture; configured.complete(capture) }
                }
                override fun onConfigureFailed(capture: CameraCaptureSession) {
                    configured.completeExceptionally(CameraException(CameraError.UNSUPPORTED, "Не удалось настроить сессию"))
                }
            }, session.handler)
            val capture = withTimeout(10_000) { configured.await() }
            val c = manager.getCameraCharacteristics(config.cameraId.value)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(output)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.profile.minFps, config.profile.maxFps))
                if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in (c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()))
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }.build()
            capture.setRepeatingRequest(request, null, session.handler)
            session.mutableState.value = CameraState.Capturing
            session
        } catch (e: Throwable) {
            withContext(NonCancellable) { session.close() }
            if (e is SecurityException) throw CameraException(CameraError.PERMISSION_DENIED, "Нет разрешения камеры", e)
            throw e
        }
      } } catch (e: Throwable) {
          withContext(NonCancellable) { allocated?.close() }
          throw e
      }
    }

    private class Session(val release: () -> Unit) : CameraSession {
        // Process-wide callback thread also handles a late onOpened after start cancellation.
        val handler = Handler(callbackThread.looper)
        val closed = AtomicBoolean()
        val mutableState = MutableStateFlow<CameraState>(CameraState.Starting)
        override val state = mutableState.asStateFlow()
        @Volatile var device: CameraDevice? = null
        @Volatile var capture: CameraCaptureSession? = null
        val deviceClosed = CompletableDeferred<Unit>()
        fun fail(e: CameraException) { mutableState.value = CameraState.Failed(e.code, e.message.orEmpty()) }
        override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
            if (!closed.compareAndSet(false, true)) return@withContext
            try {
                runCatching { capture?.stopRepeating() }
                runCatching { capture?.close() }
                device?.let { it.close(); withTimeoutOrNull(3_000) { deviceClosed.await() } }
                mutableState.value = CameraState.Closed
            } finally { release() }
        }
        companion object { private val callbackThread = HandlerThread("Camera2Callbacks").apply { start() } }
    }
}
