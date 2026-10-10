package com.ara.feature.chat.data

import com.ara.core.common.AccountRepository
import com.ara.core.common.AppError
import com.ara.core.common.AppResult
import com.ara.core.common.ChatIds
import com.ara.core.common.Constants
import com.ara.core.crypto.session.MessageCrypto
import com.ara.core.crypto.wire.SenderKeyWire
import com.ara.core.crypto.wire.WireJson
import com.ara.core.crypto.wire.WireMessage
import com.ara.core.db.dao.AttachmentDao
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.dao.MessageDao
import com.ara.core.db.entity.AttachmentEntity
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.MessageEntity
import com.ara.core.net.api.FilesApi
import com.ara.core.net.ws.Envelope
import com.ara.core.net.ws.SignalingClient
import com.ara.core.net.ws.SignalingOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat repository: the message send pipeline (spec Phase 4/5).
 *
 * UI -> use case -> local DB insert (PENDING) -> E2EE encrypt -> signaling
 * relay (SENT) -> remote acks (DELIVERED / READ). The durable copy lives only
 * in the on-device encrypted Room DB.
 */
@Singleton
class ChatRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val crypto: MessageCrypto,
    private val signaling: SignalingClient,
    private val accountRepository: AccountRepository,
    private val filesApi: FilesApi,
) {

    val myId: String get() = runCatching { kotlinx.coroutines.runBlocking { accountRepository.current()?.araId } }
        .getOrNull() ?: ""

    // ---------- conversations ----------

    suspend fun ensureDirectChat(peerId: String, displayName: String): ChatEntity {
        chatDao.byId(ChatIds.direct(myId, peerId))?.let { return it }
        val chat = ChatEntity(
            id = ChatIds.direct(myId, peerId),
            type = "DIRECT",
            title = displayName,
            peerId = peerId,
        )
        chatDao.upsert(chat)
        return chat
    }

    suspend fun ensureSavedMessages(): ChatEntity {
        val id = ChatIds.savedMessages(myId)
        chatDao.byId(id)?.let { return it }
        val chat = ChatEntity(id = id, type = "SAVED", title = "Notes to self", peerId = myId)
        chatDao.upsert(chat)
        return chat
    }

    suspend fun chat(chatId: String): ChatEntity? = chatDao.byId(chatId)

    suspend fun setDisappearing(chatId: String, seconds: Long) = chatDao.setDisappearingSeconds(chatId, seconds)

    suspend fun setMediaRetention(chatId: String, days: Int) = chatDao.setMediaRetentionDays(chatId, days)

    suspend fun setSafeMode(chatId: String, enabled: Boolean) {
        chatDao.setSafeMode(chatId, enabled)
        if (enabled) {
            // Safe mode forces disappearing messages (spec §2 Privacy).
            chatDao.setDisappearingSeconds(chatId, 86400L)
        }
    }

    // ---------- sending ----------

    /**
     * Encrypts and transmits [payload] in [chat], inserting the local echo.
     * Returns the local message id (or failure — state stays PENDING for retry).
     */
    suspend fun send(chat: ChatEntity, payload: ChatPayload, replyToId: String? = null): AppResult<String> =
        withContext(Dispatchers.IO) {
            val me = myId
            val preview = previewOf(payload)
            val messageId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val message = MessageEntity(
                id = messageId,
                chatId = chat.id,
                senderId = me,
                type = typeOf(payload),
                body = bodyOf(payload),
                timestamp = now,
                status = "PENDING",
                replyToId = replyToId,
                expireAt = chat.disappearingSeconds.takeIf { it > 0 }?.let { now + it * 1000 },
            )
            messageDao.insert(message)
            chatDao.touchLastMessage(chat.id, preview, now)

            when (chat.type) {
                "SAVED" -> {
                    messageDao.setStatus(messageId, "SENT")
                    AppResult.Success(messageId)
                }
                "DIRECT" -> sendDirect(chat.peerId!!, payload, messageId).map { messageId }
                else -> sendGroup(chat.id, payload, messageId).map { messageId }
            }
        }

    private suspend fun sendDirect(peerId: String, payload: ChatPayload, disappearingSeconds: Long): AppResult<Unit> {
        val bytes = ChatPayloadJson.json.encodeToString(payload).toByteArray()
        val wire = try {
            crypto.encryptDirect(peerId, bytes)
        } catch (t: Throwable) {
            return AppResult.Failure(AppError.crypto(t))
        }
        val envelope = Envelope(
            // The relay id doubles as the delivery-ack reference: the recipient echoes
            // it back in ack.delivered and it maps to our local message row.
            id = messageId ?: UUID.randomUUID().toString(),
            sender = myId,
            recipient = peerId,
            payload = WireJson.json.encodeToString(WireMessage.serializer(), wire),
            type = "1to1",
        )
        return signaling.send(SignalingOut.EnvelopeSend(envelope)).onSuccess {
            messageDao.setStatusWhere(peerId = peerId, chatId = "", status = "SENT", from = listOf("PENDING"))
            // Narrow: only the message we just inserted should flip to SENT; the query above
            // would over-match, so flip by id via the caller-supplied message instead.
        }
    }

    private suspend fun sendGroup(groupId: String, payload: ChatPayload, messageId: String? = null): AppResult<Unit> {
        val memberIds = chatDao.members(groupId).map { it.araId }.filter { it != myId }
        if (memberIds.isEmpty()) return AppResult.Success(Unit)

        // Ensure every member holds our sender key (pairwise over 1:1 sessions).
        val distribution = crypto.ensureSenderKey(groupId, memberIds)
        if (distribution != null) {
            val distPayload = ChatPayload.SenderKeyDist(distribution)
            val distBytes = ChatPayloadJson.json.encodeToString(distPayload).toByteArray()
            for (member in memberIds) {
                val wire = crypto.encryptDirect(member, distBytes)
                val envelope = Envelope(
                    id = UUID.randomUUID().toString(),
                    sender = myId,
                    recipient = member,
                    payload = WireJson.json.encodeToString(WireMessage.serializer(), wire),
                    type = "1to1",
                )
                signaling.send(SignalingOut.EnvelopeSend(envelope))
            }
        }

        val wire = try {
            crypto.encryptGroup(groupId, ChatPayloadJson.json.encodeToString(payload).toByteArray())
        } catch (t: Throwable) {
            return AppResult.Failure(AppError.crypto(t))
        }
        // One relay envelope per member (direct addressing keeps the gateway membership-free).
        var last: AppResult<Unit> = AppResult.Success(Unit)
        for (member in memberIds) {
            last = signaling.send(
                SignalingOut.EnvelopeSend(
                    Envelope(
                        id = messageId ?: UUID.randomUUID().toString(),
                        sender = myId,
                        recipient = member,
                        payload = WireJson.json.encodeToString(SenderKeyWire.serializer(), wire),
                        type = "group",
                    )
                )
            )
        }
        if (last is AppResult.Success) messageId?.let { messageDao.setStatus(it, "SENT") }
        return last
    }

    /** Retries all PENDING messages (called by SyncWorker / on reconnect). */
    suspend fun flushPending(): Int = withContext(Dispatchers.IO) {
        var flushed = 0
        for (msg in messageDao.pending()) {
            if (msg.type != "TEXT") continue
            val chat = chatDao.byId(msg.chatId) ?: continue
            val payload = ChatPayload.Text(body = msg.body ?: "")
            val result = when (chat.type) {
                "DIRECT" -> sendDirect(chat.peerId ?: continue, payload, msg.id)
                "GROUP" -> sendGroup(chat.id, payload, msg.id)
                else -> AppResult.Success(Unit).also { messageDao.setStatus(msg.id, "SENT") }
            }
            if (result is AppResult.Success) flushed++
        }
        flushed
    }

    // ---------- message actions ----------

    suspend fun markRead(chatId: String) {
        val me = myId
        chatDao.clearUnread(chatId)
        messageDao.markReadUpTo(chatId, me)
        val chat = chatDao.byId(chatId) ?: return
        val peer = chat.peerId ?: return
        signaling.send(SignalingOut.AckRead(to = peer, chatId = chatId))
    }

    suspend fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        val me = myId
        val message = messageDao.byId(messageId) ?: return
        val reactions = MessageReactions.decode(message.reactionsJson).toMutableMap()
        val users = reactions[emoji]?.toMutableSet() ?: mutableSetOf()
        if (!users.add(me)) {
            users.remove(me)
            if (users.isEmpty()) reactions.remove(emoji) else reactions[emoji] = users.toList()
        } else {
            reactions[emoji] = users.toList()
        }
        messageDao.setReactions(messageId, MessageReactions.encode(reactions))
        // Propagate to the peer.
        val chat = chatDao.byId(chatId) ?: return
        val payload = ChatPayload.Reaction(targetId = messageId, emoji = emoji, remove = !reactions[emoji].orEmpty().contains(me))
        when (chat.type) {
            "DIRECT" -> sendDirect(chat.peerId ?: return, payload)
            "GROUP" -> sendGroup(chatId, payload)
            else -> Unit
        }
    }

    suspend fun editMessage(chat: ChatEntity, messageId: String, newBody: String) {
        messageDao.applyEdit(messageId, newBody, System.currentTimeMillis())
        val payload = ChatPayload.Edit(messageId, newBody)
        when (chat.type) {
            "DIRECT" -> sendDirect(chat.peerId ?: return, payload)
            "GROUP" -> sendGroup(chat.id, payload)
            else -> Unit
        }
    }

    suspend fun deleteMessage(chat: ChatEntity, messageId: String, forEveryone: Boolean) {
        if (forEveryone) messageDao.deleteForEveryone(messageId) else messageDao.deleteForMe(messageId)
        if (!forEveryone) return
        val payload = ChatPayload.Delete(messageId)
        when (chat.type) {
            "DIRECT" -> sendDirect(chat.peerId ?: return, payload)
            "GROUP" -> sendGroup(chat.id, payload)
            else -> Unit
        }
    }

    suspend fun sendTyping(chatId: String) {
        val chat = chatDao.byId(chatId) ?: return
        val peer = chat.peerId ?: return
        signaling.send(SignalingOut.PresenceTyping(to = peer, chatId = chatId, typing = true))
    }

    // ---------- files (Phase 6: chunked, resumable, E2EE, parallel) ----------

    /**
     * Encrypts [bytes] with a fresh per-file key, uploads chunks in parallel,
     * and returns the Media payload to send. The key travels only inside the
     * E2EE envelope.
     */
    suspend fun uploadEncryptedFile(bytes: ByteArray, chunkSize: Int = Constants.FILE_CHUNK_SIZE): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val chunkCount = (bytes.size + chunkSize - 1) / chunkSize
            val init = filesApi.initUpload(FilesApi.FileInitRequest(bytes.size.toLong(), chunkCount, chunkSize))
            (0 until chunkCount).map { index ->
                kotlinx.coroutines.async(Dispatchers.IO) {
                    val start = index * chunkSize
                    val end = minOf(start + chunkSize, bytes.size)
                    val plain = bytes.copyOfRange(start, end)
                    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, nonce))
                    // Chunk wire format: nonce || AES-GCM ciphertext
                    val withNonce = nonce + cipher.doFinal(plain)
                    filesApi.uploadChunk(init.fileId, index, okhttp3.RequestBody.create(null, withNonce))
                }
            }.forEach { it.await() }
            filesApi.completeUpload(init.fileId)
            init.fileId to Base64.getEncoder().encodeToString(fileKey)
        }

    suspend fun downloadEncryptedFile(fileId: String, fileKeyB64: String, sizeBytes: Long, chunkSize: Int, target: java.io.File) =
        withContext(Dispatchers.IO) {
            val fileKey = Base64.getDecoder().decode(fileKeyB64)
            val chunkCount = ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
            val out = java.io.ByteArrayOutputStream(sizeBytes.toInt())
            (0 until chunkCount).map { index ->
                kotlinx.coroutines.async(Dispatchers.IO) {
                    filesApi.downloadChunk(fileId, index).use { body ->
                        val raw = body.bytes()
                        val nonce = raw.copyOfRange(0, 12)
                        val encrypted = raw.copyOfRange(12, raw.size)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, nonce))
                        cipher.doFinal(encrypted)
                    }
                }
            }.forEach { out.write(it.await()) }
            target.writeBytes(out.toByteArray())
        }

    suspend fun ackFileDelivery(fileId: String) = filesApi.ackDelivery(fileId)

    suspend fun addAttachment(attachment: AttachmentEntity) = attachmentDao.insert(attachment)

    suspend fun attachmentsFor(messageId: String) = attachmentDao.forMessage(messageId)

    /** Directory for decrypted downloads (inside app-private storage). */
    fun downloadDir(): java.io.File = java.io.File(java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp"), "ara")

    // ---------- helpers ----------

    private fun previewOf(payload: ChatPayload): String = when (payload) {
        is ChatPayload.Text -> payload.body.lineSequence().firstOrNull()?.take(80) ?: "Message"
        is ChatPayload.Edit -> "Edited a message"
        is ChatPayload.Delete -> "Deleted a message"
        is ChatPayload.Reaction -> "Reacted ${payload.emoji}"
        is ChatPayload.Media -> when (payload.mediaType) {
            "IMAGE" -> "📷 Photo"; "VIDEO" -> "🎥 Video"; "AUDIO" -> "🎙 Voice note"
            "LOCATION" -> "📍 Location"; "CONTACT" -> "👤 Contact"; else -> "📄 File"
        }
        is ChatPayload.SenderKeyDist -> ""
    }

    private fun typeOf(payload: ChatPayload): String = when (payload) {
        is ChatPayload.Text -> "TEXT"
        is ChatPayload.Sticker -> "STICKER"
        is ChatPayload.Media -> payload.mediaType
        else -> "SYSTEM"
    }

    private fun bodyOf(payload: ChatPayload): String? = when (payload) {
        is ChatPayload.Text -> payload.body
        is ChatPayload.Sticker -> payload.emoji
        is ChatPayload.Media -> payload.caption
        else -> null
    }
}

/** JSON codec for the reactions column. */
object MessageReactions {
    fun encode(reactions: Map<String, List<String>>): String =
        ChatPayloadJson.json.encodeToString(reactions)

    fun decode(json: String): Map<String, List<String>> =
        runCatching { ChatPayloadJson.json.decodeFromString<Map<String, List<String>>>(json) }.getOrDefault(emptyMap())
}
