package dev.localstream.media

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RTCStats
import java.math.BigInteger

class RtcPeerStatsTest {
    @Test fun filtersAudioAndRemoteCountersAndSupportsUnsignedValues() {
        val stats = outboundVideoStats(listOf(
            RTCStats(0, "outbound-rtp", "v1", mapOf("kind" to "video", "bytesSent" to BigInteger("4000000000"), "framesEncoded" to 30L, "totalEncodeTime" to 0.3, "qualityLimitationReason" to "cpu")),
            RTCStats(0, "outbound-rtp", "v2", mapOf("mediaType" to "video", "bytesSent" to 100L, "framesEncoded" to 5, "totalEncodeTime" to 0.1)),
            RTCStats(0, "outbound-rtp", "audio", mapOf("kind" to "audio", "bytesSent" to 999L)),
            RTCStats(0, "remote-outbound-rtp", "remote", mapOf("kind" to "video", "bytesSent" to 999L)),
        ))
        assertEquals(4_000_000_100L, stats.bytesSent)
        assertEquals(35L, stats.framesEncoded)
        assertEquals(0.4, stats.encodeSeconds!!, 0.00001)
        assertEquals("cpu", stats.qualityLimit)
    }
    @Test fun absentMeasurementsAreNotZero() {
        val stats = outboundVideoStats(emptyList())
        assertNull(stats.bytesSent)
        assertNull(stats.framesEncoded)
        assertNull(stats.encodeSeconds)
        assertNull(stats.framesPerSecond)
    }
}
