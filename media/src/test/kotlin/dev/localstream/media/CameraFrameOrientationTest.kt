package dev.localstream.media

import dev.localstream.camera.api.LensFacing
import org.junit.Assert.*
import org.junit.Test

class CameraFrameOrientationTest {
    @Test fun backCameraAllDisplayAndSensorRotations() {
        val expected = arrayOf(intArrayOf(0, 270, 180, 90), intArrayOf(90, 0, 270, 180),
            intArrayOf(180, 90, 0, 270), intArrayOf(270, 180, 90, 0))
        for (sensor in 0..3) for (display in 0..3)
            assertEquals(expected[sensor][display], cameraFrameRotation(sensor * 90, display * 90, LensFacing.BACK))
    }
    @Test fun frontCameraHasOppositeDisplayRotationSign() {
        val expected = arrayOf(intArrayOf(0, 90, 180, 270), intArrayOf(90, 180, 270, 0),
            intArrayOf(180, 270, 0, 90), intArrayOf(270, 0, 90, 180))
        for (sensor in 0..3) for (display in 0..3)
            assertEquals(expected[sensor][display], cameraFrameRotation(sensor * 90, display * 90, LensFacing.FRONT))
    }
    @Test fun correctionRemapsAsymmetricTextureCornersWithoutMovingCenter() {
        fun map(matrix: FloatArray, x: Float, y: Float) = floatArrayOf(
            matrix[0] * x + matrix[1] * y + matrix[2], matrix[3] * x + matrix[4] * y + matrix[5])
        val back = cameraTextureCorrection(90, LensFacing.BACK)
        assertArrayEquals(floatArrayOf(0f, 1f), map(back, 0f, 0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), map(back, 1f, 0f), 0f)
        assertArrayEquals(floatArrayOf(1f, 1f), map(back, 0f, 1f), 0f)
        val front = cameraTextureCorrection(270, LensFacing.FRONT)
        assertArrayEquals(floatArrayOf(0f, 0f), map(front, 0f, 0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), map(front, 1f, 0f), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f), map(front, 0f, 1f), 0f)
        for (facing in listOf(LensFacing.BACK, LensFacing.FRONT)) for (sensor in listOf(0, 90, 180, 270))
            assertArrayEquals(floatArrayOf(.5f, .5f), map(cameraTextureCorrection(sensor, facing), .5f, .5f), 0f)
    }
    @Test fun invalidAnglesAreRejectedInsteadOfSilentlyRotatingTheWrongWay() {
        assertThrows(IllegalArgumentException::class.java) { cameraFrameRotation(45, 0, LensFacing.BACK) }
        assertThrows(IllegalArgumentException::class.java) { cameraFrameRotation(90, 1, LensFacing.BACK) }
    }
}
