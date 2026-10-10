package com.ara.core.crypto.keys

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64
import kotlinx.serialization.encodeToString

/** JSON/B64 codec for raw key material and ciphertext inside protocol messages. */
object B64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ByteArrayB64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(Base64.getEncoder().encodeToString(value))

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}

/**
 * Public prekey bundle published by a user, fetched via
 * `GET /v1/users/{araId}/keybundle` for X3DH session setup.
 */
@Serializable
data class KeyBundle(
    val araId: String,
    @Serializable(with = B64Serializer::class) val identityX25519: ByteArray,
    @Serializable(with = B64Serializer::class) val identityEd25519: ByteArray,
    val signedPreKeyId: Int,
    @Serializable(with = B64Serializer::class) val signedPreKey: ByteArray,
    @Serializable(with = B64Serializer::class) val signedPreKeySignature: ByteArray,
    val oneTimePreKeyId: Int? = null,
    @Serializable(with = B64Serializer::class) val oneTimePreKey: ByteArray? = null,
)

/** A one-time prekey pair held on device. */
data class OneTimePreKey(
    val id: Int,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

/** Public half of a one-time prekey, uploaded to the backend for bundling. */
@Serializable
data class OneTimePreKeyUpload(
    val id: Int,
    @Serializable(with = B64Serializer::class) val publicKey: ByteArray,
)

/** Our long-term identity: an X25519 pair for DH and an Ed25519 pair for signing. */
data class IdentityKeyPair(
    val x25519Private: ByteArray,
    val x25519Public: ByteArray,
    val ed25519Private: ByteArray,
    val ed25519Public: ByteArray,
) {
    companion object {
        fun generate(): IdentityKeyPair {
            val xPriv = com.ara.core.crypto.primitives.Crypto.generateX25519PrivateKey()
            val ePriv = com.ara.core.crypto.primitives.Crypto.generateEd25519PrivateKey()
            return IdentityKeyPair(
                x25519Private = xPriv,
                x25519Public = com.ara.core.crypto.primitives.Crypto.x25519PublicKey(xPriv),
                ed25519Private = ePriv,
                ed25519Public = com.ara.core.crypto.primitives.Crypto.ed25519PublicKey(ePriv),
            )
        }
    }
}

/** Signed prekey pair (ratcheted periodically) held on device. */
data class SignedPreKey(
    val id: Int,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val signature: ByteArray,
)
