package dev.localstream.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1
const val SERVICE_TYPE = "_localcam._tcp."
const val MAX_VIEWERS = 5
val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Generation identifies a PeerConnection, not a WebSocket. Late ICE is discarded. */
@Serializable
data class Signal(
    val type: String,
    val version: Int = PROTOCOL_VERSION,
    val clientId: String = "",
    val streamId: String = "",
    val serverId: String = "",
    val token: String = "",
    val accessCode: String = "",
    val generation: String = "",
    val sdp: String = "",
    val candidate: String = "",
    val mid: String = "",
    val line: Int = 0,
    val code: String = "",
    val message: String = "",
)

object SignalType {
    const val JOIN = "join"
    const val WELCOME = "welcome"
    const val OFFER = "offer"
    const val ANSWER = "answer"
    const val ICE = "ice"
    const val RESTART = "restart"
    const val RECREATE = "recreate"
    const val LEAVE = "leave"
    const val ENDED = "ended"
    const val ERROR = "error"
}
