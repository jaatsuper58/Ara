package com.ara.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ara.core.db.entity.AttachmentEntity
import com.ara.core.db.entity.CallLogEntity
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.ContactEntity
import com.ara.core.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE isArchived = 0 ORDER BY isPinned DESC, lastMessageAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun byId(id: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeById(id: String): Flow<ChatEntity?>

    @Query("UPDATE chats SET lastMessagePreview = :preview, lastMessageAt = :timestamp WHERE id = :id")
    suspend fun touchLastMessage(id: String, preview: String?, timestamp: Long)

    @Query("UPDATE chats SET unreadCount = unreadCount + :delta WHERE id = :id")
    suspend fun bumpUnread(id: String, delta: Int)

    @Query("UPDATE chats SET unreadCount = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE chats SET isMuted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean)

    @Query("UPDATE chats SET disappearingSeconds = :seconds WHERE id = :id")
    suspend fun setDisappearingSeconds(id: String, seconds: Long)

    @Query("UPDATE chats SET mediaRetentionDays = :days WHERE id = :id")
    suspend fun setMediaRetentionDays(id: String, days: Int)

    @Query("UPDATE chats SET safeMode = :enabled WHERE id = :id")
    suspend fun setSafeMode(id: String, enabled: Boolean)

    @Query("UPDATE chats SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: String, draft: String?)

    @Query("UPDATE chats SET myRole = :role WHERE id = :id")
    suspend fun setMyRole(id: String, role: String)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)

    // ----- group members (cached) -----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<com.ara.core.db.entity.GroupMemberEntity>)

    @Query("SELECT * FROM group_members WHERE chatId = :chatId ORDER BY role, displayName")
    fun observeMembers(chatId: String): Flow<List<com.ara.core.db.entity.GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE chatId = :chatId")
    suspend fun members(chatId: String): List<com.ara.core.db.entity.GroupMemberEntity>

    @Query("DELETE FROM group_members WHERE chatId = :chatId AND araId = :araId")
    suspend fun removeMember(chatId: String, araId: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND deletedForMe = 0 ORDER BY timestamp ASC")
    fun observeChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeById(id: String): Flow<MessageEntity?>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE chatId = :chatId AND senderId = :senderId AND status IN (:from)")
    suspend fun setStatusWhere(chatId: String, senderId: String, status: String, from: List<String>)

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE senderId = :peerId AND status = 'SENT'")
    suspend fun markIncomingDelivered(peerId: String)

    @Query("UPDATE messages SET status = 'READ' WHERE chatId = :chatId AND senderId = :myId AND status IN ('SENT','DELIVERED')")
    suspend fun markReadUpTo(chatId: String, myId: String)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("UPDATE messages SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE messages SET body = :body, editedAt = :editedAt WHERE id = :id")
    suspend fun applyEdit(id: String, body: String, editedAt: Long)

    @Query("UPDATE messages SET body = NULL, deletedForEveryone = 1 WHERE id = :id")
    suspend fun deleteForEveryone(id: String)

    @Query("UPDATE messages SET deletedForMe = 1 WHERE id = :id")
    suspend fun deleteForMe(id: String)

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE id = :id")
    suspend fun setReactions(id: String, reactionsJson: String)

    @Query("UPDATE messages SET expireAt = :expireAt WHERE chatId = :chatId AND expireAt IS NULL AND senderId != :me")
    suspend fun backfillExpireAt(chatId: String, me: String, expireAt: Long)

    @Query("SELECT * FROM messages WHERE status = 'PENDING' ORDER BY timestamp ASC LIMIT 100")
    suspend fun pending(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE expireAt IS NOT NULL AND expireAt <= :now")
    suspend fun purgeExpired(now: Long): Int

    @Query(
        """
        DELETE FROM messages WHERE chatId IN (:chatIds) AND timestamp <= :cutoff
        """
    )
    suspend fun purgeOlderThan(chatIds: List<String>, cutoff: Long): Int

    @Query(
        """
        SELECT messages.* FROM messages
        JOIN messages_fts ON messages.rowid = messages_fts.rowid
        WHERE messages_fts MATCH :query AND messages.deletedForMe = 0
        ORDER BY messages.timestamp DESC LIMIT 200
        """
    )
    suspend fun search(query: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteChatMessages(chatId: String)
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun forMessage(messageId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    fun observeForMessage(messageId: String): Flow<List<AttachmentEntity>>

    @Query("UPDATE attachments SET state = :state, localPath = COALESCE(:localPath, localPath) WHERE id = :id")
    suspend fun updateState(id: String, state: String, localPath: String? = null)

    @Query("SELECT * FROM attachments WHERE chatId IN (:chatIds) AND localPath IS NOT NULL AND mimeType LIKE :mimePrefix || '%'")
    suspend fun mediaForChats(chatIds: List<String>, mimePrefix: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE chatId = :chatId")
    fun observeForChat(chatId: String): Flow<List<AttachmentEntity>>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE isBlocked = 0 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE araId = :araId")
    suspend fun byId(araId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE araId LIKE '%' || :query || '%' OR displayName LIKE '%' || :query || '%' LIMIT 50")
    suspend fun search(query: String): List<ContactEntity>

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE araId = :araId")
    suspend fun setBlocked(araId: String, blocked: Boolean)

    @Query("UPDATE contacts SET isFavorite = :favorite WHERE araId = :araId")
    suspend fun setFavorite(araId: String, favorite: Boolean)

    @Query("SELECT * FROM contacts WHERE isBlocked = 1")
    suspend fun blocked(): List<ContactEntity>
}

@Dao
interface CallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(call: CallLogEntity)

    @Update
    suspend fun update(call: CallLogEntity)

    @Query("SELECT * FROM call_log ORDER BY startedAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<CallLogEntity>>

    @Query("SELECT COUNT(*) FROM call_log WHERE direction = 'MISSED' AND status = 'MISSED'")
    fun observeMissedCount(): Flow<Int>

    @Query("DELETE FROM call_log WHERE id = :id")
    suspend fun delete(id: String)
}
