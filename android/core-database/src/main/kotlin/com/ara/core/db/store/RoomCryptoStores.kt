package com.ara.core.db.store

import com.ara.core.crypto.group.OwnedSenderKey
import com.ara.core.crypto.group.RemoteSenderKey
import com.ara.core.crypto.ratchet.RatchetState
import com.ara.core.crypto.store.RemoteIdentityStore
import com.ara.core.crypto.store.SenderKeyStore
import com.ara.core.crypto.store.SessionStore
import com.ara.core.db.dao.KeyDao
import com.ara.core.db.entity.CryptoSessionEntity
import com.ara.core.db.entity.RemoteIdentityEntity
import com.ara.core.db.entity.SenderKeyCoverageEntity
import com.ara.core.db.entity.SenderKeyEntity
import com.ara.core.crypto.session.SessionCodec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed double-ratchet session persistence (inside the encrypted DB). */
@Singleton
class RoomSessionStore @Inject constructor(
    private val keyDao: KeyDao,
) : SessionStore {

    override suspend fun load(peerId: String): RatchetState? =
        keyDao.loadSession(peerId)?.let { SessionCodec.deserialize(it.stateJson) }

    override suspend fun save(peerId: String, state: RatchetState) {
        keyDao.saveSession(CryptoSessionEntity(peerId, SessionCodec.serialize(state)))
    }

    override suspend fun delete(peerId: String) = keyDao.deleteSession(peerId)
}

@Singleton
class RoomRemoteIdentityStore @Inject constructor(
    private val keyDao: KeyDao,
) : RemoteIdentityStore {

    override suspend fun saveRemoteIdentity(araId: String, identityX25519: ByteArray, identityEd25519: ByteArray) {
        val b64 = Base64.getEncoder()
        keyDao.saveRemoteIdentity(
            RemoteIdentityEntity(
                araId = araId,
                identityX25519 = b64.encodeToString(identityX25519),
                identityEd25519 = b64.encodeToString(identityEd25519),
            )
        )
    }

    override suspend fun remoteIdentity(araId: String): RemoteIdentityStore.RemoteIdentity? {
        val b64 = Base64.getDecoder()
        return keyDao.remoteIdentity(araId)?.let {
            RemoteIdentityStore.RemoteIdentity(
                araId = it.araId,
                identityX25519 = b64.decode(it.identityX25519),
                identityEd25519 = b64.decode(it.identityEd25519),
                verified = it.verified,
            )
        }
    }

    override suspend fun markVerified(araId: String, verified: Boolean) {
        keyDao.setVerified(araId, verified)
    }
}

@Singleton
class RoomSenderKeyStore @Inject constructor(
    private val keyDao: KeyDao,
) : SenderKeyStore {

    override suspend fun saveOwned(key: OwnedSenderKey) {
        val b64 = Base64.getEncoder()
        keyDao.saveSenderKey(
            SenderKeyEntity(
                groupId = key.groupId,
                senderId = OWNED_SENTINEL,
                distributionId = key.distributionId,
                chainKeySeed = b64.encodeToString(key.chainKeySeed),
                iteration = key.iteration,
                isOwned = true,
            )
        )
    }

    override suspend fun ownedForGroup(groupId: String): OwnedSenderKey? {
        val b64 = Base64.getDecoder()
        return keyDao.ownedSenderKey(groupId)?.let {
            OwnedSenderKey(
                groupId = it.groupId,
                distributionId = it.distributionId,
                chainKeySeed = b64.decode(it.chainKeySeed),
                iteration = it.iteration,
            )
        }
    }

    override suspend fun saveRemote(key: RemoteSenderKey) {
        val b64 = Base64.getEncoder()
        keyDao.saveSenderKey(
            SenderKeyEntity(
                groupId = key.groupId,
                senderId = key.senderId,
                distributionId = key.distributionId,
                chainKeySeed = b64.encodeToString(key.chainKeySeed),
                iteration = key.iteration,
                isOwned = false,
            )
        )
    }

    override suspend fun remoteFor(groupId: String, senderId: String, distributionId: String): RemoteSenderKey? {
        val b64 = Base64.getDecoder()
        return keyDao.senderKey(groupId, senderId, distributionId)?.let {
            RemoteSenderKey(
                groupId = it.groupId,
                senderId = it.senderId,
                distributionId = it.distributionId,
                chainKeySeed = b64.decode(it.chainKeySeed),
                iteration = it.iteration,
            )
        }
    }

    override suspend fun coveredMembers(groupId: String): Set<String> =
        keyDao.coveredMembers(groupId).toSet()

    override suspend fun markCovered(groupId: String, memberIds: List<String>) {
        keyDao.markCovered(memberIds.map { SenderKeyCoverageEntity(groupId, it) })
    }

    private companion object {
        const val OWNED_SENTINEL = "__self__"
    }
}
