package com.ara.core.crypto.store

import com.ara.core.crypto.group.OwnedSenderKey
import com.ara.core.crypto.group.RemoteSenderKey
import com.ara.core.crypto.keys.IdentityKeyPair
import com.ara.core.crypto.keys.KeyBundle
import com.ara.core.crypto.keys.OneTimePreKey
import com.ara.core.crypto.keys.SignedPreKey
import com.ara.core.crypto.ratchet.RatchetState

/** Persists double-ratchet session state per peer (Room-backed on device). */
interface SessionStore {
    suspend fun load(peerId: String): RatchetState?
    suspend fun save(peerId: String, state: RatchetState)
    suspend fun delete(peerId: String)
}

/** Our identity keys and signed/one-time prekeys. */
interface IdentityKeyStore {
    /** Lazily generates and persists the identity on first access. */
    suspend fun identity(): IdentityKeyPair
    suspend fun currentSignedPreKey(): SignedPreKey
    suspend fun rotateSignedPreKey(): SignedPreKey
    suspend fun popOneTimePreKey(id: Int): OneTimePreKey?
    suspend fun oneTimePreKeyCount(): Int
    suspend fun addOneTimePreKeys(count: Int): List<OneTimePreKey>
    suspend fun allOneTimePreKeys(): List<OneTimePreKey>
}

/** Remote identities seen on the wire — the basis for safety-number verification. */
interface RemoteIdentityStore {
    suspend fun saveRemoteIdentity(araId: String, identityX25519: ByteArray, identityEd25519: ByteArray)
    suspend fun remoteIdentity(araId: String): RemoteIdentity?
    suspend fun markVerified(araId: String, verified: Boolean)
    data class RemoteIdentity(
        val araId: String,
        val identityX25519: ByteArray,
        val identityEd25519: ByteArray,
        val verified: Boolean = false,
    )
}

/** Fetches a public prekey bundle from the backend for a given Ara ID. */
fun interface KeyBundleFetcher {
    suspend fun fetch(araId: String): KeyBundle
}

/** Persists sender keys for group messaging. */
interface SenderKeyStore {
    suspend fun saveOwned(key: OwnedSenderKey)
    suspend fun ownedForGroup(groupId: String): OwnedSenderKey?
    suspend fun saveRemote(key: RemoteSenderKey)
    suspend fun remoteFor(groupId: String, senderId: String, distributionId: String): RemoteSenderKey?
    /** Members of [groupId] we have already sent our sender-key distribution to. */
    suspend fun coveredMembers(groupId: String): Set<String>
    suspend fun markCovered(groupId: String, memberIds: List<String>)
}
