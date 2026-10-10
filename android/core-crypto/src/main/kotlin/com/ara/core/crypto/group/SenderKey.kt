package com.ara.core.crypto.group

import com.ara.core.crypto.primitives.Crypto
import com.ara.core.crypto.wire.SenderKeyDistribution
import com.ara.core.crypto.wire.SenderKeyWire

/** A sender key owned by us (for broadcasting to a group). */
data class OwnedSenderKey(
    val groupId: String,
    val distributionId: String,
    val chainKeySeed: ByteArray,
    var iteration: Int = 0,
)

/** Received (cached) sender key of another group member. */
data class RemoteSenderKey(
    val groupId: String,
    val senderId: String,
    val distributionId: String,
    val chainKeySeed: ByteArray,
    val iteration: Int,
)

/**
 * Signal-style Sender Keys for group messaging: each member holds one
 * symmetric sender key that they distribute pairwise over double-ratchet
 * sessions; group messages are then a single AES-GCM encryption (O(1) per
 * message) with per-iteration message keys derived HKDF-style.
 */
object SenderKeyCodec {

    private val MESSAGE_KEY_INFO = "AraGroupMessageKey".toByteArray()

    fun createKey(groupId: String): OwnedSenderKey =
        OwnedSenderKey(
            groupId = groupId,
            distributionId = java.util.UUID.randomUUID().toString(),
            chainKeySeed = Crypto.randomBytes(32),
        )

    fun toDistribution(key: OwnedSenderKey): SenderKeyDistribution =
        SenderKeyDistribution(key.groupId, key.distributionId, key.chainKeySeed, key.iteration)

    /** mk_i = HKDF(ikm = iteration, salt = chainKeySeed) — same derivation both sides. */
    fun messageKey(key: SenderKeyDistribution, iteration: Int): ByteArray {
        val ikm = ByteArray(8)
        for (i in 0 until 8) ikm[i] = ((iteration.toLong() ushr ((7 - i) * 8)) and 0xFF).toByte()
        return Crypto.hkdf(ikm, key.chainKeySeed, MESSAGE_KEY_INFO, 32)
    }

    fun encrypt(key: OwnedSenderKey, plaintext: ByteArray): SenderKeyWire {
        val distribution = toDistribution(key)
        val messageKey = messageKey(distribution, key.iteration)
        val nonce = Crypto.generateNonce()
        val ciphertext = Crypto.aesGcmEncrypt(messageKey, nonce, plaintext, key.groupId.toByteArray())
        java.util.Arrays.fill(messageKey, 0)
        val wire = SenderKeyWire(
            groupId = key.groupId,
            distributionId = key.distributionId,
            iteration = key.iteration,
            nonce = nonce,
            ciphertext = ciphertext,
        )
        key.iteration += 1 // every message derives a fresh key from the seed chain
        return wire
    }

    fun decrypt(key: SenderKeyDistribution, wire: SenderKeyWire): ByteArray {
        val messageKey = messageKey(key, wire.iteration)
        return Crypto.aesGcmDecrypt(messageKey, wire.nonce, wire.ciphertext, wire.groupId.toByteArray())
    }
}
