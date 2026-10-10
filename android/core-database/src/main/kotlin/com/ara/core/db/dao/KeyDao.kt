package com.ara.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ara.core.db.entity.CryptoSessionEntity
import com.ara.core.db.entity.RemoteIdentityEntity
import com.ara.core.db.entity.SenderKeyCoverageEntity
import com.ara.core.db.entity.SenderKeyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyDao {
    // ----- ratchet sessions -----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: CryptoSessionEntity)

    @Query("SELECT * FROM crypto_sessions WHERE peerId = :peerId")
    suspend fun loadSession(peerId: String): CryptoSessionEntity?

    @Query("DELETE FROM crypto_sessions WHERE peerId = :peerId")
    suspend fun deleteSession(peerId: String)

    // ----- remote identities -----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRemoteIdentity(identity: RemoteIdentityEntity)

    @Query("SELECT * FROM remote_identities WHERE araId = :araId")
    suspend fun remoteIdentity(araId: String): RemoteIdentityEntity?

    @Query("UPDATE remote_identities SET verified = :verified WHERE araId = :araId")
    suspend fun setVerified(araId: String, verified: Boolean)

    // ----- sender keys -----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSenderKey(key: SenderKeyEntity)

    @Query("SELECT * FROM sender_keys WHERE groupId = :groupId AND senderId = :senderId AND distributionId = :distributionId LIMIT 1")
    suspend fun senderKey(groupId: String, senderId: String, distributionId: String): SenderKeyEntity?

    @Query("SELECT * FROM sender_keys WHERE groupId = :groupId AND isOwned = 1 LIMIT 1")
    suspend fun ownedSenderKey(groupId: String): SenderKeyEntity?

    @Query("SELECT memberId FROM sender_key_coverage WHERE groupId = :groupId")
    fun observeCoveredMembers(groupId: String): Flow<List<String>>

    @Query("SELECT memberId FROM sender_key_coverage WHERE groupId = :groupId")
    suspend fun coveredMembers(groupId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markCovered(coverage: List<SenderKeyCoverageEntity>)
}
