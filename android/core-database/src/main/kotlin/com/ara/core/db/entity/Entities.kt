package com.ara.core.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Conversation: direct chat, group, or the "notes to self" thread. */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    /** DIRECT | GROUP | SAVED */
    val type: String,
    val title: String,
    /** Peer Ara ID for DIRECT chats; null otherwise. */
    val peerId: String? = null,
    val avatarPath: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long = 0,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    /** Disappearing-message timer in seconds; 0 = off. */
    val disappearingSeconds: Long = 0,
    /** Per-chat media retention in days; -1 = forever. */
    val mediaRetentionDays: Int = -1,
    /** Safe mode: block screenshots + force disappearing messages. */
    val safeMode: Boolean = false,
    val draft: String? = null,
    val wallpaperPath: String? = null,
    /** Owner/member role for groups: OWNER | ADMIN | MEMBER */
    val myRole: String = "MEMBER",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "messages",
    indices = [Index("chatId", "timestamp"), Index("status"), Index("expireAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    /** TEXT | IMAGE | VIDEO | FILE | AUDIO | LOCATION | CONTACT | SYSTEM */
    val type: String,
    val body: String? = null,
    val timestamp: Long,
    /** PENDING | SENT | DELIVERED | READ | RECEIVED | FAILED */
    var status: String,
    val replyToId: String? = null,
    val forwardedFrom: String? = null,
    val editedAt: Long? = null,
    val starred: Boolean = false,
    val pinned: Boolean = false,
    val deletedForEveryone: Boolean = false,
    val deletedForMe: Boolean = false,
    /** JSON map reactionEmoji -> list of member ids. */
    val reactionsJson: String = "{}",
    /** JSON array of mentioned Ara IDs. */
    val mentionsJson: String = "[]",
    /** Disappearing deadline (epoch ms); null = keep. */
    val expireAt: Long? = null,
)

@Entity(
    tableName = "attachments",
    indices = [Index("messageId"), Index("chatId"), Index("remoteFileId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val chatId: String,
    val localPath: String? = null,
    val remoteFileId: String? = null,
    val mimeType: String,
    val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbPath: String? = null,
    /** Per-file AES key (E2EE chunks) — arrives inside the encrypted envelope only. */
    val fileKey: String? = null,
    /** LOCAL | REMOTE | DOWNLOADING | DONE | FAILED */
    var state: String = "LOCAL",
)

@Entity(tableName = "contacts", indices = [Index("displayName")])
data class ContactEntity(
    @PrimaryKey val araId: String,
    val displayName: String,
    val avatarPath: String? = null,
    val phoneNumber: String? = null,
    val isBlocked: Boolean = false,
    val isFavorite: Boolean = false,
    val lastSeenAt: Long? = null,
    val statusVisible: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "call_log", indices = [Index("startedAt")])
data class CallLogEntity(
    @PrimaryKey val id: String,
    val chatId: String? = null,
    val peerId: String,
    val isGroup: Boolean = false,
    val isVideo: Boolean = false,
    /** INCOMING | OUTGOING | MISSED */
    val direction: String,
    /** COMPLETED | MISSED | DECLINED | FAILED */
    val status: String,
    val startedAt: Long,
    val durationMs: Long = 0,
)

/** Cached group membership (synced from the Groups REST API). */
@Entity(tableName = "group_members", primaryKeys = ["chatId", "araId"])
data class GroupMemberEntity(
    val chatId: String,
    val araId: String,
    val displayName: String,
    /** OWNER | ADMIN | MEMBER */
    val role: String = "MEMBER",
    val joinedAt: Long = 0,
)
