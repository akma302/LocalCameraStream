package dev.localstream.camera.api

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

@JvmInline value class CameraId(val value: String)
enum class LensFacing { BACK, FRONT, EXTERNAL }
data class CameraInfo(val id: CameraId, val facing: LensFacing, val sensorOrientation: Int)
data class CaptureProfile(val width: Int, val height: Int, val minFps: Int, val maxFps: Int)
data class CameraCapabilities(val profiles: List<CaptureProfile>)
data class CaptureConfig(val cameraId: CameraId, val profile: CaptureProfile)
enum class CameraError { BUSY, PERMISSION_DENIED, DISCONNECTED, UNSUPPORTED, DEVICE_FAILURE }
class CameraException(val code: CameraError, message: String, cause: Throwable? = null) : Exception(message, cause)
sealed interface CameraState {
    data object Starting : CameraState
    data object Capturing : CameraState
    data object Closed : CameraState
    data class Failed(val code: CameraError, val message: String) : CameraState
}

/** The caller configures and owns [Surface]; release it only AFTER session.close(). */
interface CameraApi {
    suspend fun listCameras(): List<CameraInfo>
    suspend fun getCapabilities(cameraId: CameraId): CameraCapabilities
    suspend fun startCapture(config: CaptureConfig, output: Surface): CameraSession
}

interface CameraSession {
    val state: StateFlow<CameraState>
    /** Idempotent; waits for camera resources to stop using the output surface. */
    suspend fun close()
}

fun CameraCapabilities.preferredProfile(): CaptureProfile = profiles.minByOrNull {
    kotlin.math.abs(it.width - 1280) + kotlin.math.abs(it.height - 720) +
        kotlin.math.abs(it.maxFps - 30) * 100
} ?: throw CameraException(CameraError.UNSUPPORTED, "Нет доступных профилей видеозахвата")
