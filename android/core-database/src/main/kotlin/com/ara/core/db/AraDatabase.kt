package com.ara.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ara.core.db.dao.AttachmentDao
import com.ara.core.db.dao.CallLogDao
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.dao.ContactDao
import com.ara.core.db.dao.KeyDao
import com.ara.core.db.dao.MessageDao
import com.ara.core.db.entity.AttachmentEntity
import com.ara.core.db.entity.CallLogEntity
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.ContactEntity
import com.ara.core.db.entity.CryptoSessionEntity
import com.ara.core.db.entity.GroupMemberEntity
import com.ara.core.db.entity.MessageEntity
import com.ara.core.db.entity.MessageFtsEntity
import com.ara.core.db.entity.RemoteIdentityEntity
import com.ara.core.db.entity.SenderKeyCoverageEntity
import com.ara.core.db.entity.SenderKeyEntity

/**
 * On-device source of truth. SQLCipher-encrypted (see DatabaseModule) — the
 * database file on disk is unreadable without the Keystore-wrapped key.
 */
@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        AttachmentEntity::class,
        ContactEntity::class,
        CallLogEntity::class,
        CryptoSessionEntity::class,
        RemoteIdentityEntity::class,
        SenderKeyEntity::class,
        SenderKeyCoverageEntity::class,
        GroupMemberEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AraDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun contactDao(): ContactDao
    abstract fun callLogDao(): CallLogDao
    abstract fun keyDao(): KeyDao
}
