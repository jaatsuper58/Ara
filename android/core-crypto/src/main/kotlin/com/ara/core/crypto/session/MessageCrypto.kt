package com.ara.core.crypto.session

import com.ara.core.crypto.group.OwnedSenderKey
import com.ara.core.crypto.group.RemoteSenderKey
import com.ara.core.crypto.group.SenderKeyCodec
import com.ara.core.crypto.store.IdentityKeyStore
import com.ara.core.crypto.store.KeyBundleFetcher
import com.ara.core.crypto.store.RemoteIdentityStore
import com.ara.core.crypto.store.SenderKeyStore
import com.ara.core.crypto.store.SessionStore
import com.ara.core.crypto.wire.SenderKeyDistribution
import com.ara.core.crypto.wire.SenderKeyWire
import com.ara.core.crypto.wire.WireMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level message crypto used by the messaging pipeline.
 *
 * 1:1  -> X3DH + Double Ratchet ([SessionCipher])
 * Group -> Sender Keys, distributed pairwise over 1:1 sessions
 */
@Singleton
class MessageCrypto @Inject constructor(
    sessionStore: SessionStore,
    identityStore: IdentityKeyStore,
    remoteIdentityStore: RemoteIdentityStore,
    senderKeyStore: SenderKeyStore,
    bundleFetcher: KeyBundleFetcher,
) {

    private val sessionCipher = SessionCipher(sessionStore, identityStore, remoteIdentityStore, bundleFetcher)
    private val senderKeys = SenderKeys(senderKeyStore)

    // ---------- 1:1 ----------

    suspend fun encryptDirect(peerId: String, plaintext: ByteArray): WireMessage =
        sessionCipher.encrypt(peerId, plaintext)

    suspend fun decryptDirect(peerId: String, message: WireMessage): ByteArray =
        sessionCipher.decrypt(peerId, message)

    suspend fun hasDirectSession(peerId: String): Boolean = sessionCipher.hasSession(peerId)

    // ---------- group ----------

    /**
     * Encrypts [plaintext] for [groupId]. The caller must have called
     * [ensureSenderKey] (which triggers pairwise distribution) at least once
     * per group before sending.
     */
    suspend fun encryptGroup(groupId: String, plaintext: ByteArray): SenderKeyWire =
        senderKeys.encrypt(groupId, plaintext)

    suspend fun decryptGroup(senderId: String, wire: SenderKeyWire): ByteArray =
        senderKeys.decrypt(senderId, wire)

    /**
     * Returns the sender-key distribution payload to send (pairwise-encrypted)
     * to [members] who do not yet have our key, or null if everyone is covered.
     */
    suspend fun ensureSenderKey(groupId: String, members: List<String>): SenderKeyDistribution? =
        senderKeys.ensureOwned(groupId, members)

    suspend fun applySenderKeyDistribution(senderId: String, distribution: SenderKeyDistribution) =
        senderKeys.applyDistribution(senderId, distribution)

    suspend fun groupMemberCoverage(groupId: String): Set<String> = senderKeys.coverage(groupId)

    // ---------- verification ----------

    suspend fun remoteIdentity(araId: String): RemoteIdentityStore.RemoteIdentity? =
        remoteIdentityStore.remoteIdentity(araId)

    suspend fun markIdentityVerified(araId: String, verified: Boolean) =
        remoteIdentityStore.markVerified(araId, verified)

    suspend fun safetyNumberWith(peerId: String): String {
        val mine = identityStore.identity()
        val theirs = remoteIdentityStore.remoteIdentity(peerId)
            ?: error("No identity recorded for $peerId yet")
        return SafetyNumber.compute(
            myId = "",
            myIdentity = mine.x25519Public,
            theirId = peerId,
            theirIdentity = theirs.identityX25519,
        )
    }
}

/** Internal sender-key state machine over [SenderKeyStore]. */
private class SenderKeys(private val store: SenderKeyStore) {

    suspend fun ensureOwned(groupId: String, members: List<String>): SenderKeyDistribution? {
        val owned = store.ownedForGroup(groupId) ?: SenderKeyCodec.createKey(groupId).also {
            store.saveOwned(it)
        }
        val covered = store.coveredMembers(groupId)
        val missing = members.filter { it !in covered }
        if (missing.isEmpty()) return null
        store.markCovered(groupId, missing)
        return SenderKeyCodec.toDistribution(owned)
    }

    suspend fun encrypt(groupId: String, plaintext: ByteArray): SenderKeyWire {
        val owned = store.ownedForGroup(groupId)
            ?: SenderKeyCodec.createKey(groupId).also { store.saveOwned(it) }
        return SenderKeyCodec.encrypt(owned, plaintext)
    }

    suspend fun decrypt(senderId: String, wire: SenderKeyWire): ByteArray {
        val remote = store.remoteFor(groupId = wire.groupId, senderId = senderId, distributionId = wire.distributionId)
            ?: error("No sender key from $senderId for ${wire.groupId} — distribution message missing")
        val distribution = SenderKeyDistribution(
            groupId = remote.groupId,
            distributionId = remote.distributionId,
            chainKeySeed = remote.chainKeySeed,
            iteration = remote.iteration,
        )
        return SenderKeyCodec.decrypt(distribution, wire)
    }

    suspend fun applyDistribution(senderId: String, distribution: SenderKeyDistribution) {
        store.saveRemote(
            RemoteSenderKey(
                groupId = distribution.groupId,
                senderId = senderId,
                distributionId = distribution.distributionId,
                chainKeySeed = distribution.chainKeySeed,
                iteration = distribution.iteration,
            )
        )
    }

    suspend fun coverage(groupId: String): Set<String> = store.coveredMembers(groupId)
}

/** 60-digit safety number for out-of-band identity verification. */
object SafetyNumber {

    /**
     * Deterministic, order-independent fingerprint of both identity keys:
     * SHA-512 over (smallerId || key || largerId || key), rendered as
     * six space-separated 10-digit groups (60 digits, like Signal).
     */
    fun compute(myId: String, myIdentity: ByteArray, theirId: String, theirIdentity: ByteArray): String {
        val firstId: String
        val firstKey: ByteArray
        val secondId: String
        val secondKey: ByteArray
        if (myId <= theirId) {
            firstId = myId; firstKey = myIdentity; secondId = theirId; secondKey = theirIdentity
        } else {
            firstId = theirId; firstKey = theirIdentity; secondId = myId; secondKey = myIdentity
        }

        val buffer = java.io.ByteArrayOutputStream().apply {
            write(firstId.toByteArray(Charsets.UTF_8))
            write(firstKey)
            write(secondId.toByteArray(Charsets.UTF_8))
            write(secondKey)
        }
        val digest = com.ara.core.crypto.primitives.Crypto.sha512(buffer.toByteArray())

        val digits = StringBuilder()
        var offset = 0
        while (digits.length < 60) {
            val chunk = digest.copyOfRange(offset % (digest.size - 4), (offset % (digest.size - 4)) + 4)
            val value = ((chunk[0].toLong() and 0xFF) shl 24) or
                ((chunk[1].toLong() and 0xFF) shl 16) or
                ((chunk[2].toLong() and 0xFF) shl 8) or
                (chunk[3].toLong() and 0xFF)
            digits.append(String.format("%010d", value % 100_000_000_000L / 1000))
            offset += 4
        }
        return digits.toString().chunked(10).joinToString(" ")
    }
}
