package com.ara.core.crypto.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.ara.core.crypto.keys.B64Serializer

/**
 * Encrypted transport messages exchanged inside `envelope.send/receive`.
 * The polymorphic `type` discriminator lets a recipient route prekey
 * handshake messages and normal ratchet messages.
 */
@Serializable
sealed class WireMessage {

    /** First message of a session: carries X3DH parameters + the initial ratchet payload. */
    @Serializable
    @SerialName("pk")
    data class PreKeyWire(
        @Serializable(with = B64Serializer::class) val identityX25519: ByteArray,
        @Serializable(with = B64Serializer::class) val identityEd25519: ByteArray,
        @Serializable(with = B64Serializer::class) val ephemeralPublic: ByteArray,
        val signedPreKeyId: Int,
        val oneTimePreKeyId: Int? = null,
        val header: RatchetHeaderDto,
        @Serializable(with = B64Serializer::class) val nonce: ByteArray,
        @Serializable(with = B64Serializer::class) val ciphertext: ByteArray,
    ) : WireMessage()

    /** Normal double-ratchet message. */
    @Serializable
    @SerialName("rk")
    data class RatchetWire(
        val header: RatchetHeaderDto,
        @Serializable(with = B64Serializer::class) val nonce: ByteArray,
        @Serializable(with = B64Serializer::class) val ciphertext: ByteArray,
    ) : WireMessage()
}

/** JSON-safe copy of [com.ara.core.crypto.ratchet.RatchetHeader]. */
@Serializable
data class RatchetHeaderDto(
    @Serializable(with = B64Serializer::class) val dhPub: ByteArray,
    val pn: Int,
    val n: Int,
)

/** Sender-key group message (group chats). */
@Serializable
@SerialName("sk")
data class SenderKeyWire(
    val groupId: String,
    val distributionId: String,
    val iteration: Int,
    @Serializable(with = B64Serializer::class) val nonce: ByteArray,
    @Serializable(with = B64Serializer::class) val ciphertext: ByteArray,
)

/** Sender-key distribution payload, sent pairwise-encrypted to each group member. */
@Serializable
data class SenderKeyDistribution(
    val groupId: String,
    val distributionId: String,
    @Serializable(with = B64Serializer::class) val chainKeySeed: ByteArray,
    val iteration: Int,
)

object WireJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }
}
