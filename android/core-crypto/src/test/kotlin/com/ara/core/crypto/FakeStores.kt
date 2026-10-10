package com.ara.core.crypto

import com.ara.core.crypto.group.OwnedSenderKey
import com.ara.core.crypto.group.RemoteSenderKey
import com.ara.core.crypto.keys.IdentityKeyPair
import com.ara.core.crypto.keys.KeyBundle
import com.ara.core.crypto.keys.OneTimePreKey
import com.ara.core.crypto.keys.SignedPreKey
import com.ara.core.crypto.primitives.Crypto
import com.ara.core.crypto.ratchet.RatchetState
import com.ara.core.crypto.session.SessionCodec
import com.ara.core.crypto.store.IdentityKeyStore
import com.ara.core.crypto.store.KeyBundleFetcher
import com.ara.core.crypto.store.RemoteIdentityStore
import com.ara.core.crypto.store.SenderKeyStore
import com.ara.core.crypto.store.SessionStore

/** In-memory store implementations shared by the JVM crypto tests. */
class FakeSessionStore : SessionStore {
    val sessions = mutableMapOf<String, String>()
    override suspend fun load(peerId: String): RatchetState? =
        sessions[peerId]?.let(SessionCodec::deserialize)
    override suspend fun save(peerId: String, state: RatchetState) {
        sessions[peerId] = SessionCodec.serialize(state)
    }
    override suspend fun delete(peerId: String) { sessions.remove(peerId) }
}

class FakeIdentityKeyStore : IdentityKeyStore {
    var identityPair: IdentityKeyPair? = null
    var signedPreKey: SignedPreKey? = null
    val oneTimeKeys = mutableListOf<OneTimePreKey>()
    private var nextSpkId = 1
    private var nextOpkId = 1

    override suspend fun identity(): IdentityKeyPair =
        identityPair ?: IdentityKeyPair.generate().also { identityPair = it }

    override suspend fun currentSignedPreKey(): SignedPreKey =
        signedPreKey ?: createSignedPreKey()

    override suspend fun rotateSignedPreKey(): SignedPreKey = createSignedPreKey()

    private suspend fun createSignedPreKey(): SignedPreKey {
        val priv = Crypto.generateX25519PrivateKey()
        val pub = Crypto.x25519PublicKey(priv)
        val identity = identity()
        val spk = SignedPreKey(
            id = nextSpkId++,
            privateKey = priv,
            publicKey = pub,
            signature = Crypto.ed25519Sign(identity.ed25519Private, pub),
        )
        signedPreKey = spk
        return spk
    }

    override suspend fun popOneTimePreKey(id: Int): OneTimePreKey? =
        oneTimeKeys.firstOrNull { it.id == id }?.also { oneTimeKeys.remove(it) }

    override suspend fun oneTimePreKeyCount(): Int = oneTimeKeys.size

    override suspend fun addOneTimePreKeys(count: Int): List<OneTimePreKey> {
        val added = (0 until count).map {
            val priv = Crypto.generateX25519PrivateKey()
            OneTimePreKey(nextOpkId++, priv, Crypto.x25519PublicKey(priv))
        }
        oneTimeKeys.addAll(added)
        return added
    }

    override suspend fun allOneTimePreKeys(): List<OneTimePreKey> = oneTimeKeys.toList()
}

class FakeRemoteIdentityStore : RemoteIdentityStore {
    val identities = mutableMapOf<String, RemoteIdentity>()
    override suspend fun saveRemoteIdentity(araId: String, identityX25519: ByteArray, identityEd25519: ByteArray) {
        identities[araId] = RemoteIdentity(araId, identityX25519, identityEd25519)
    }
    override suspend fun remoteIdentity(araId: String): RemoteIdentity? = identities[araId]
    override suspend fun markVerified(araId: String, verified: Boolean) {
        identities[araId]?.let { identities[araId] = it.copy(verified = verified) }
    }
}

class FakeSenderKeyStore : SenderKeyStore {
    val owned = mutableMapOf<String, OwnedSenderKey>()
    val remote = mutableMapOf<String, RemoteSenderKey>()
    val covered = mutableMapOf<String, MutableSet<String>>()

    override suspend fun saveOwned(key: OwnedSenderKey) { owned[key.groupId] = key }
    override suspend fun ownedForGroup(groupId: String): OwnedSenderKey? = owned[groupId]
    override suspend fun saveRemote(key: RemoteSenderKey) {
        remote["${key.groupId}|${key.senderId}|${key.distributionId}"] = key
    }
    override suspend fun remoteFor(groupId: String, senderId: String, distributionId: String): RemoteSenderKey? =
        remote["$groupId|$senderId|$distributionId"]
    override suspend fun coveredMembers(groupId: String): Set<String> = covered[groupId]?.toSet() ?: emptySet()
    override suspend fun markCovered(groupId: String, memberIds: List<String>) {
        covered.getOrPut(groupId) { mutableSetOf() }.addAll(memberIds)
    }
}

/** Serves bundles for multiple local key stores — simulates the backend for tests. */
class FakeBundleDirectory {
    val stores = mutableMapOf<String, FakeIdentityKeyStore>()

    fun register(araId: String, store: FakeIdentityKeyStore) {
        stores[araId] = store
    }

    val fetcher = KeyBundleFetcher { araId ->
        val store = stores.getValue(araId)
        val identity = store.identity()
        val spk = store.currentSignedPreKey()
        val opk = store.allOneTimePreKeys().firstOrNull()
        KeyBundle(
            araId = araId,
            identityX25519 = identity.x25519Public,
            identityEd25519 = identity.ed25519Public,
            signedPreKeyId = spk.id,
            signedPreKey = spk.publicKey,
            signedPreKeySignature = spk.signature,
            oneTimePreKeyId = opk?.id,
            oneTimePreKey = opk?.publicKey,
        )
    }
}
