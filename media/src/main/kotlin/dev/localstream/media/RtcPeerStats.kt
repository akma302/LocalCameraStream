package dev.localstream.media

import org.webrtc.RTCStats

data class RtcPeerStats(
    val bytesSent: Long?,
    val framesEncoded: Long?,
    val encodeSeconds: Double?,
    val framesPerSecond: Double?,
    val qualityLimit: String,
)

/** Missing platform counters stay null rather than being reported as measured zeroes. */
internal fun outboundVideoStats(stats: Collection<RTCStats>): RtcPeerStats {
    val outbound = stats.filter { it.type == "outbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "video" }
    fun numbers(key: String): List<Number>? {
        val values = outbound.map { it.members[key] as? Number }
        return if (values.isNotEmpty() && values.none { it == null }) values.filterNotNull() else null
    }
    fun longs(key: String): Long? = numbers(key)?.sumOf { it.toLong() }
    fun doubles(key: String): Double? = numbers(key)?.sumOf { it.toDouble() }
    return RtcPeerStats(longs("bytesSent"), longs("framesEncoded"), doubles("totalEncodeTime"), doubles("framesPerSecond"),
        outbound.mapNotNull { it.members["qualityLimitationReason"] as? String }.distinct().joinToString("+"))
}
