package dev.localstream.media

import android.graphics.Matrix
import dev.localstream.camera.api.CameraInfo
import dev.localstream.camera.api.LensFacing
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame

/** WebRTC clockwise frame metadata; display degrees use Android's Surface rotation. */
internal fun cameraFrameRotation(sensorDegrees: Int, displayDegrees: Int, facing: LensFacing): Int {
    require(sensorDegrees in setOf(0, 90, 180, 270))
    require(displayDegrees in setOf(0, 90, 180, 270))
    val device = if (facing == LensFacing.FRONT) displayDegrees else -displayDegrees
    return (sensorDegrees + device + 360) % 360
}

/** T(center) * mirror(front) * R(-sensor) * T(-center), in Android Matrix order.
 * Camera2's SurfaceTexture matrix already includes sensor orientation and front
 * mirroring. Undo those before expressing orientation as WebRTC frame metadata.
 * Keep the producer's crop/vertical-flip matrix: applyTransformMatrix composes
 * this correction with it rather than replacing the producer matrix. */
internal fun cameraTextureCorrection(sensorDegrees: Int, facing: LensFacing): FloatArray {
    val quarter = ((360 - sensorDegrees) % 360) / 90
    val cos = floatArrayOf(1f, 0f, -1f, 0f)[quarter]
    val sin = floatArrayOf(0f, 1f, 0f, -1f)[quarter]
    val mirror = if (facing == LensFacing.FRONT) -1f else 1f
    val a = mirror * cos
    val b = -mirror * sin
    return floatArrayOf(a, b, .5f - .5f * (a + b), sin, cos, .5f - .5f * (sin + cos), 0f, 0f, 1f)
}

/** Returns an owned reference. Caller releases it; the input remains caller-owned. */
internal fun orientCameraFrame(frame: VideoFrame, info: CameraInfo, displayDegrees: Int): VideoFrame {
    val rotation = cameraFrameRotation(info.sensorOrientation, displayDegrees, info.facing)
    val texture = frame.buffer as TextureBufferImpl // SurfaceTextureHelper's documented buffer type.
    val correction = Matrix().apply { setValues(cameraTextureCorrection(info.sensorOrientation, info.facing)) }
    val buffer = texture.applyTransformMatrix(correction, texture.width, texture.height)
    return VideoFrame(buffer, rotation, frame.timestampNs)
}
