package com.ara.core.crypto.x3dh

import com.ara.core.crypto.keys.IdentityKeyPair
import com.ara.core.crypto.keys.KeyBundle
import com.ara.core.crypto.primitives.Crypto
import com.ara.core.crypto.primitives.X3DH_F
import com.ara.core.crypto.keys.B64Serializer
import kotlinx.serialization.Serializable

/**
 * Extended Triple Diffie-Hellman (Signal spec) for initial 1:1 session setup.
 *
 * Initiator (A) -> Responder (B):
 *   DH1 = DH(IK_A, SPK_B)
 *   DH2 = DH(EK_A, IK_B)
 *   DH3 = DH(EK_A, SPK_B)
 *   DH4 = DH(EK_A, OPK_B)   (optional)
 *   SK  = HKDF(F || DH1 || DH2 || DH3 [|| DH4])
 */
object X3dh {

    private val INFO = "AraX3DH".toByteArray()
    private val ZERO_SALT = ByteArray(32)

    @Serializable
    data class InitiatorResult(
        @Serializable(with = B64Serializer::class) val sharedKey: ByteArray,
        @Serializable(with = B64Serializer::class) val ephemeralPublic: ByteArray,
        val usedSignedPreKeyId: Int,
        val usedOneTimePreKeyId: Int? = null,
    )

    /** Verifies the bundle signature, runs X3DH and returns the initial shared secret. */
    fun initiate(ourIdentity: IdentityKeyPair, theirBundle: KeyBundle): InitiatorResult {
        // Authenticate the signed prekey with the responder's Ed25519 identity key.
        require(
            Crypto.ed25519Verify(
                publicKey = theirBundle.identityEd25519,
                message = theirBundle.signedPreKey,
                signature = theirBundle.signedPreKeySignature,
            )
        ) { "Signed prekey signature verification failed — possible MITM" }

        val ephemeralPrivate = Crypto.generateX25519PrivateKey()
        val ephemeralPublic = Crypto.x25519PublicKey(ephemeralPrivate)

        val dh1 = Crypto.x25519(ourIdentity.x25519Private, theirBundle.signedPreKey)
        val dh2 = Crypto.x25519(ephemeralPrivate, theirBundle.identityX25519)
        val dh3 = Crypto.x25519(ephemeralPrivate, theirBundle.signedPreKey)
        val dh4 = theirBundle.oneTimePreKey?.let { Crypto.x25519(ephemeralPrivate, it) }

        val ikm = X3DH_F + dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))
        val sk = Crypto.hkdf(ikm, ZERO_SALT, INFO, 32)
        java.util.Arrays.fill(ikm, 0)

        return InitiatorResult(
            sharedKey = sk,
            ephemeralPublic = ephemeralPublic,
            usedSignedPreKeyId = theirBundle.signedPreKeyId,
            usedOneTimePreKeyId = theirBundle.oneTimePreKeyId,
        )
    }

    /** Mirror computation on the responder side using our private prekeys. */
    fun respond(
        ourIdentity: IdentityKeyPair,
        ourSignedPreKeyPrivate: ByteArray,
        ourOneTimePreKeyPrivate: ByteArray?,
        theirIdentityX25519: ByteArray,
        theirEphemeralPublic: ByteArray,
    ): ByteArray {
        val dh1 = Crypto.x25519(ourSignedPreKeyPrivate, theirIdentityX25519)
        val dh2 = Crypto.x25519(ourIdentity.x25519Private, theirEphemeralPublic)
        val dh3 = Crypto.x25519(ourSignedPreKeyPrivate, theirEphemeralPublic)
        val dh4 = ourOneTimePreKeyPrivate?.let { Crypto.x25519(it, theirEphemeralPublic) }

        val ikm = X3DH_F + dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))
        val sk = Crypto.hkdf(ikm, ZERO_SALT, INFO, 32)
        java.util.Arrays.fill(ikm, 0)
        return sk
    }
}
