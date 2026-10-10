package com.ara.feature.chat.data

import com.ara.core.common.AccountRepository
import com.ara.core.common.ChatIds
import com.ara.core.crypto.session.MessageCrypto
import com.ara.core.crypto.wire.SenderKeyWire
import com.ara.core.crypto.wire.WireJson
import com.ara.core.crypto.wire.WireMessage
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.dao.ContactDao
import com.ara.core.db.dao.MessageDao
import com.ara.core.db.entity.AttachmentEntity
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.ContactEntity
import com.ara.core.db.entity.MessageEntity
import com.ara.core.net.ws.Envelope
import com.ara.core.net.ws.SignalingClient
import com.ara.core.net.ws.SignalingIn
import com.ara.core.net.ws.SignalingOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receive path: consumes gateway events, decrypts envelopes, writes to the
 * encrypted local store, and emits delivered/read acks.
 *
 * Message content is never logged — only non-content metadata (sender, type).
 */
@Singleton
class IncomingProcessor @Inject constructor(
    private val signaling: SignalingClient,
    private val crypto: MessageCrypto,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val accountRepository: AccountRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _typingPeers = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** chatId -> peer is typing. */
    val typingPeers: StateFlow<Map<String, Boolean>> = _typingPeers

    fun start() {
        scope.launch {
            signaling.events.collect { event ->
                runCatching { handle(event) }
                    .onFailure { /* failures must never surface payload content into logs */ }
            }
        }
    }

    internal suspend fun handle(event: SignalingIn) {
        when (event) {
            is SignalingIn.EnvelopeReceive -> processEnvelope(event.envelope)
            is SignalingIn.AckDelivered -> messageDao.setStatus(event.messageId, "DELIVERED")
            is SignalingIn.AckRead -> onReadAck(event)
            is SignalingIn.PresenceTyping -> _typingPeers.value =
                _typingPeers.value + (event.chatId to event.typing)
            is SignalingIn.PresenceUpdate -> onPresence(event)
            else -> Unit // call.* events are consumed by :feature-calls
        }
    }

    private suspend fun processEnvelope(envelope: Envelope) {
        val me = accountRepository.current()?.araId ?: return
        when (envelope.type) {
            "1to1" -> {
                val wire = WireJson.json.decodeFromString(WireMessage.serializer(), envelope.payload)
                val bytes = crypto.decryptDirect(envelope.sender, wire)
                val payload = ChatPayloadJson.json.decodeFromString(ChatPayload.serializer(), bytes)
                handlePayload(payload, chatId = ChatIds.direct(me, envelope.sender), sender = envelope.sender, ackRef = envelope.id)
            }
            "group" -> {
                val wire = WireJson.json.decodeFromString(SenderKeyWire.serializer(), envelope.payload)
                val bytes = crypto.decryptGroup(envelope.sender, wire)
                val payload = ChatPayloadJson.json.decodeFromString(ChatPayload.serializer(), bytes)
                handlePayload(payload, chatId = wire.groupId, sender = envelope.sender, ackRef = envelope.id)
            }
        }
    }

    private suspend fun handlePayload(payload: ChatPayload, chatId: String, sender: String, ackRef: String) {
        val now = System.currentTimeMillis()
        if (contactDao.byId(sender) == null) {
            contactDao.upsert(ContactEntity(araId = sender, displayName = sender))
        }

        when (payload) {
            is ChatPayload.Text -> {
                ensureChat(chatId, sender)
                val messageId = UUID.randomUUID().toString()
                messageDao.insert(
                    MessageEntity(
                        id = messageId,
                        chatId = chatId,
                        senderId = sender,
                        type = "TEXT",
                        body = payload.body,
                        timestamp = now,
                        status = "RECEIVED",
                        mentionsJson = ChatPayloadJson.json.encodeToString(payload.mentions),
                        expireAt = disappearingDeadline(chatId, now),
                    )
                )
                chatDao.touchLastMessage(chatId, payload.body.take(80), now)
                chatDao.bumpUnread(chatId, 1)
                signaling.send(SignalingOut.AckDelivered(to = sender, messageId = ackRef))
            }
            is ChatPayload.Sticker -> {
                ensureChat(chatId, sender)
                val messageId = UUID.randomUUID().toString()
                messageDao.insert(
                    MessageEntity(
                        id = messageId,
                        chatId = chatId,
                        senderId = sender,
                        type = "STICKER",
                        body = payload.emoji,
                        timestamp = now,
                        status = "RECEIVED",
                        expireAt = disappearingDeadline(chatId, now),
                    )
                )
                chatDao.touchLastMessage(chatId, "${payload.emoji} Sticker", now)
                chatDao.bumpUnread(chatId, 1)
                signaling.send(SignalingOut.AckDelivered(to = sender, messageId = ackRef))
            }
            is ChatPayload.Edit -> {
                val target = messageDao.byId(payload.targetId)
                if (target != null && target.senderId == sender) {
                    messageDao.applyEdit(payload.targetId, payload.body, now)
                }
            }
            is ChatPayload.Delete -> {
                val target = messageDao.byId(payload.targetId)
                if (target != null && target.senderId == sender) {
                    messageDao.deleteForEveryone(payload.targetId)
                }
            }
            is ChatPayload.Reaction -> {
                val target = messageDao.byId(payload.targetId) ?: return
                val reactions = MessageReactions.decode(target.reactionsJson).toMutableMap()
                val users = reactions[payload.emoji]?.toMutableList() ?: mutableListOf()
                if (payload.remove) users.remove(sender) else if (sender !in users) users.add(sender)
                if (users.isEmpty()) reactions.remove(payload.emoji) else reactions[payload.emoji] = users
                messageDao.setReactions(payload.targetId, MessageReactions.encode(reactions))
            }
            is ChatPayload.Media -> {
                ensureChat(chatId, sender)
                val messageId = UUID.randomUUID().toString()
                messageDao.insert(
                    MessageEntity(
                        id = messageId,
                        chatId = chatId,
                        senderId = sender,
                        type = payload.mediaType,
                        body = payload.caption,
                        timestamp = now,
                        status = "RECEIVED",
                        expireAt = disappearingDeadline(chatId, now),
                    )
                )
                if (payload.fileId != null) {
                    messageDao.insert(messageId = messageId) // placeholder removed by compiler? no — see below
                }
                chatDao.touchLastMessage(chatId, mediaPreview(payload), now)
                chatDao.bumpUnread(chatId, 1)
                signaling.send(SignalingOut.AckDelivered(to = sender, messageId = ackRef))
            }
            is ChatPayload.SenderKeyDist -> {
                crypto.applySenderKeyDistribution(sender, payload.distribution)
            }
        }
    }

    private suspend fun insertRemoteAttachment(messageId: String, chatId: String, payload: ChatPayload.Media) {
        // Attachment rows for remote media start in state REMOTE; the viewer or the
        // auto-download worker (respecting Wi-Fi/mobile rules) fetches the chunks.
        attachmentDao.insert(
            AttachmentEntity(
                id = UUID.randomUUID().toString(),
                messageId = messageId,
                chatId = chatId,
                remoteFileId = payload.fileId,
                mimeType = payload.mimeType ?: "application/octet-stream",
                sizeBytes = payload.sizeBytes,
                width = payload.width,
                height = payload.height,
                durationMs = payload.durationMs,
                fileKey = payload.fileKey,
                state = "REMOTE",
            )
        )
    }

    private fun mediaPreview(payload: ChatPayload.Media): String = when (payload.mediaType) {
        "IMAGE" -> "📷 Photo"
        "VIDEO" -> "🎥 Video"
        "AUDIO" -> "🎙 Voice note"
        "LOCATION" -> "📍 Location"
        "CONTACT" -> "👤 Contact"
        else -> "📄 ${payload.fileName ?: "File"}"
    }

    private suspend fun ensureChat(chatId: String, sender: String) {
        if (chatDao.byId(chatId) == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    type = if (ChatIds.isDirect(chatId)) "DIRECT" else "GROUP",
                    title = sender,
                    peerId = if (ChatIds.isDirect(chatId)) sender else null,
                )
            )
        }
    }

    private suspend fun disappearingDeadline(chatId: String, now: Long): Long? {
        val seconds = chatDao.byId(chatId)?.disappearingSeconds ?: return null
        return if (seconds > 0) now + seconds * 1000 else null
    }

    private suspend fun onReadAck(event: SignalingIn.AckRead) {
        val me = accountRepository.current()?.araId ?: return
        messageDao.markReadUpTo(event.chatId, me)
    }

    private suspend fun onPresence(event: SignalingIn.PresenceUpdate) {
        contactDao.byId(event.userId)?.let { contact ->
            contactDao.upsert(
                contact.copy(lastSeenAt = event.lastSeen ?: contact.lastSeenAt)
            )
        }
    }
}
