package com.ara.core.net.ws

import com.ara.core.common.Constants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Envelope relayed by the signaling gateway. The server treats [payload] as an
 * opaque string: it contains the encrypted wire message whose inner structure
 * (ciphertext, nonce) the server never parses or stores.
 *
 * Shape per spec §3/§6: sender, recipient, ciphertext+nonce (inside payload),
 * timestamp, type.
 */
@Serializable
data class Envelope(
    val id: String,
    val sender: String,
    val recipient: String,
    /** Opaque serialized encrypted payload (WireMessage / SenderKeyWire / file manifest). */
    val payload: String,
    val type: String = "1to1",
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("v") val protocolVersion: Int = Constants.PROTOCOL_VERSION,
)

/** Messages the client sends to the gateway. */
@Serializable
sealed class SignalingOut {

    @Serializable
    @SerialName("envelope.send")
    data class EnvelopeSend(val envelope: Envelope) : SignalingOut()

    @Serializable
    @SerialName("ack.delivered")
    data class AckDelivered(val to: String, val messageId: String) : SignalingOut()

    @Serializable
    @SerialName("ack.read")
    data class AckRead(val to: String, val chatId: String) : SignalingOut()

    @Serializable
    @SerialName("call.offer")
    data class CallOffer(val callId: String, val to: String, val isVideo: Boolean, val sdp: String) : SignalingOut()

    @Serializable
    @SerialName("call.answer")
    data class CallAnswer(val callId: String, val to: String, val sdp: String) : SignalingOut()

    @Serializable
    @SerialName("call.ice")
    data class CallIce(val callId: String, val to: String, val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int = 0) : SignalingOut()

    @Serializable
    @SerialName("call.end")
    data class CallEnd(val callId: String, val to: String, val reason: String = "hangup") : SignalingOut()

    @Serializable
    @SerialName("call.ringing")
    data class CallRinging(val callId: String, val to: String) : SignalingOut()

    @Serializable
    @SerialName("call.decline")
    data class CallDecline(val callId: String, val to: String) : SignalingOut()

    @Serializable
    @SerialName("call.join")
    data class CallJoin(val callId: String, val room: String) : SignalingOut()

    @Serializable
    @SerialName("presence.typing")
    data class PresenceTyping(val to: String, val chatId: String, val typing: Boolean) : SignalingOut()

    @Serializable
    @SerialName("presence.subscribe")
    data class PresenceSubscribe(val userIds: List<String>) : SignalingOut()
}

/** Messages the gateway pushes to the client. */
@Serializable
sealed class SignalingIn {

    @Serializable
    @SerialName("session.ready")
    data class SessionReady(val userId: String) : SignalingIn()

    @Serializable
    @SerialName("envelope.receive")
    data class EnvelopeReceive(val envelope: Envelope) : SignalingIn()

    @Serializable
    @SerialName("ack.delivered")
    data class AckDelivered(val messageId: String, val from: String) : SignalingIn()

    @Serializable
    @SerialName("ack.read")
    data class AckRead(val chatId: String, val from: String) : SignalingIn()

    @Serializable
    @SerialName("call.offer")
    data class CallOffer(val callId: String, val from: String, val isVideo: Boolean, val sdp: String) : SignalingIn()

    @Serializable
    @SerialName("call.answer")
    data class CallAnswer(val callId: String, val from: String, val sdp: String) : SignalingIn()

    @Serializable
    @SerialName("call.ice")
    data class CallIce(val callId: String, val from: String, val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int = 0) : SignalingIn()

    @Serializable
    @SerialName("call.end")
    data class CallEnd(val callId: String, val from: String, val reason: String = "hangup") : SignalingIn()

    @Serializable
    @SerialName("call.ringing")
    data class CallRinging(val callId: String, val from: String) : SignalingIn()

    @Serializable
    @SerialName("call.decline")
    data class CallDecline(val callId: String, val from: String) : SignalingIn()

    @Serializable
    @SerialName("call.accepted")
    data class CallAccepted(val callId: String, val from: String) : SignalingIn()

    @Serializable
    @SerialName("presence.update")
    data class PresenceUpdate(val userId: String, val online: Boolean, val lastSeen: Long? = null) : SignalingIn()

    @Serializable
    @SerialName("presence.typing")
    data class PresenceTyping(val chatId: String, val userId: String, val typing: Boolean) : SignalingIn()

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String, val refId: String? = null) : SignalingIn()
}

object SignalingJson {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "t"
        encodeDefaults = true
    }
}
