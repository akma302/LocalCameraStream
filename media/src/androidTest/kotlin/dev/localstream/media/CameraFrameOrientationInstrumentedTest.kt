package dev.localstream.media

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import dev.localstream.camera.api.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic producer matrices: no camera, permissions or private footage required. */
class CameraFrameOrientationInstrumentedTest {
    @Test fun normalizesProducerTransformPreservesCropAndBalancesBufferReferences() {
        val egl = EglBase.create()
        egl.createDummyPbufferSurface()
        egl.makeCurrent()
        val converter = YuvConverter()
        try {
            for (facing in listOf(LensFacing.BACK, LensFacing.FRONT)) {
                for (sensor in listOf(0, 90, 180, 270)) {
                    for (display in listOf(0, 90, 180, 270)) {
                        // Deliberately asymmetric crop and vertical flip. These belong
                        // to the producer and must survive orientation normalization.
                        val base = Matrix().apply { setValues(floatArrayOf(.6f, 0f, .1f, 0f, -.8f, .9f, 0f, 0f, 1f)) }
                        val producer = Matrix(base).apply {
                            preTranslate(.5f, .5f)
                            preRotate(sensor.toFloat())
                            if (facing == LensFacing.FRONT) preScale(-1f, 1f)
                            preTranslate(-.5f, -.5f)
                        }
                        val released = AtomicInteger()
                        val buffer = TextureBufferImpl(1280, 720, VideoFrame.TextureBuffer.Type.OES, 0, producer,
                            Handler(Looper.getMainLooper()), converter) { released.incrementAndGet() }
                        val input = VideoFrame(buffer, 0, 123L)
                        try {
                            val frame = orientCameraFrame(input, CameraInfo(CameraId("synthetic"), facing, sensor), display)
                            try {
                                val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, .5f, .5f)
                                val expected = corners.copyOf().also(base::mapPoints)
                                val actual = corners.copyOf().also { (frame.buffer as VideoFrame.TextureBuffer).transformMatrix.mapPoints(it) }
                                assertArrayEquals("facing=$facing sensor=$sensor display=$display", expected, actual, .00001f)
                                assertEquals(123L, frame.timestampNs)
                                val quarterTurn = frame.rotation % 180 != 0
                                assertEquals(if (quarterTurn) 720 else 1280, frame.rotatedWidth)
                                assertEquals(if (quarterTurn) 1280 else 720, frame.rotatedHeight)
                                assertEquals(0, released.get())
                            } finally { frame.release() }
                            assertEquals("Helper still owns the input reference", 0, released.get())
                        } finally { input.release() }
                        assertEquals("Exactly one release after both owners finish", 1, released.get())
                    }
                }
            }
        } finally { converter.release(); egl.release() }
    }
}
