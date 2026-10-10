package com.ara.core.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/** Serialized double-ratchet session state per peer (JSON via SessionCodec). */
@Entity(tableName = "crypto_sessions")
data class CryptoSessionEntity(
    @PrimaryKey val peerId: String,
    val stateJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Remote identity keys seen on the wire — basis for safety-number verification. */
@Entity(tableName = "remote_identities")
data class RemoteIdentityEntity(
    @PrimaryKey val araId: String,
    val identityX25519: String,
    val identityEd25519: String,
    val verified: Boolean = false,
    val firstSeenAt: Long = System.currentTimeMillis(),
)

/** Sender keys (ours to distribute + received from members) for group encryption. */
@Entity(
    tableName = "sender_keys",
    primaryKeys = ["groupId", "senderId", "distributionId"],
)
data class SenderKeyEntity(
    val groupId: String,
    val senderId: String,
    val distributionId: String,
    val chainKeySeed: String,
    val iteration: Int,
    val isOwned: Boolean = false,
)

/** Tracks which group members already hold our sender key for a group. */
@Entity(tableName = "sender_key_coverage", primaryKeys = ["groupId", "memberId"])
data class SenderKeyCoverageEntity(
    val groupId: String,
    val memberId: String,
)

/** Room FTS index over message bodies — powers full-text local search. */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    val body: String?,
)
