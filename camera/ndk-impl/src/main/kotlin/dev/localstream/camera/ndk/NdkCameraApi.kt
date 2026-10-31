package dev.localstream.camera.ndk

import android.view.Surface
import dev.localstream.camera.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class NdkCameraApi : CameraApi {
    init { System.loadLibrary("localstream_camera") }
    private val occupied = AtomicBoolean()
    override suspend fun listCameras(): List<CameraInfo> = withContext(Dispatchers.IO) {
        nativeCameras().map { row ->
            val fields = row.split('|')
            CameraInfo(CameraId(fields[0]), when (fields[1].toInt()) { 0 -> LensFacing.FRONT; 1 -> LensFacing.BACK; else -> LensFacing.EXTERNAL }, fields[2].toInt())
        }
    }
    override suspend fun getCapabilities(cameraId: CameraId): CameraCapabilities = withContext(Dispatchers.IO) {
        CameraCapabilities(nativeProfiles(cameraId.value).toList().chunked(4).map {
            CaptureProfile(it[0], it[1], it[2], it[3])
        })
    }
    override suspend fun startCapture(config: CaptureConfig, output: Surface): CameraSession {
      var allocated: Session? = null
      try { return withContext(Dispatchers.IO) {
        if (!occupied.compareAndSet(false, true)) throw CameraException(CameraError.BUSY, "Камера уже используется")
        try {
            if (config.profile !in getCapabilities(config.cameraId).profiles)
                throw CameraException(CameraError.UNSUPPORTED, "Профиль не поддерживается NDK камерой")
            val session = Session().also { allocated = it }
            val p = config.profile
            val handle = nativeStart(config.cameraId.value, output, p.width, p.height, p.minFps, p.maxFps, session)
            session.handle = handle
            if (session.mutableState.value == CameraState.Starting) session.mutableState.value = CameraState.Capturing
            session
        } catch (e: Throwable) { occupied.set(false); throw e }
      } } catch (e: Throwable) {
          withContext(NonCancellable) { allocated?.close() }
          if (e is CancellationException || e is CameraException) throw e
          throw CameraException(if (e is SecurityException) CameraError.PERMISSION_DENIED else CameraError.DEVICE_FAILURE,
              e.message ?: "Ошибка Camera NDK", e)
      }
    }
    private inner class Session : CameraSession {
        val mutableState = MutableStateFlow<CameraState>(CameraState.Starting)
        override val state = mutableState.asStateFlow()
        private val closed = AtomicBoolean()
        var handle = 0L
        @Suppress("unused") // Called by JNI; do not rename without updating camera.cpp.
        fun onNativeError(code: Int) { mutableState.value = CameraState.Failed(CameraError.DEVICE_FAILURE, "Ошибка Camera NDK: $code") }
        override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
            if (closed.compareAndSet(false, true)) {
                try { nativeClose(handle); mutableState.value = CameraState.Closed }
                finally { occupied.set(false) }
            }
        }
    }
    private external fun nativeCameras(): Array<String>
    private external fun nativeProfiles(cameraId: String): IntArray
    private external fun nativeStart(cameraId: String, surface: Surface, width: Int, height: Int, minFps: Int, maxFps: Int, callback: Any): Long
    private external fun nativeClose(handle: Long)
}
