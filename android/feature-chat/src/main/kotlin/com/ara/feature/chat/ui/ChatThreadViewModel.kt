package com.ara.feature.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ara.core.common.AppResult
import com.ara.core.crypto.session.MessageCrypto
import com.ara.core.db.dao.AttachmentDao
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.dao.MessageDao
import com.ara.core.db.entity.AttachmentEntity
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.MessageEntity
import com.ara.feature.chat.data.ChatRepository
import com.ara.feature.chat.data.ChatPayload
import com.ara.feature.chat.data.IncomingProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Base64
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatThreadViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val crypto: MessageCrypto,
    incoming: IncomingProcessor,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    val chatId: String = savedStateHandle.get<String>("chatId") ?: ""

    val myId: String = chatRepository.myId

    val allChats: StateFlow<List<ChatEntity>> = chatDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val chat: StateFlow<ChatEntity?> = chatDao.observeById(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<MessageEntity>> = chatDao.observeById(chatId)
        .flatMapLatest { chat -> if (chat == null) flowOf(emptyList()) else messageDao.observeChat(chatId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val typing: StateFlow<Boolean> = incoming.typingPeers
        .flatMapLatest { map -> flowOf(map[chatId] == true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var typingJob: Job? = null

    init {
        viewModelScope.launch { chatRepository.markRead(chatId) }
    }

    fun send(text: String, replyToId: String? = null) = viewModelScope.launch {
        val chat = chat.value ?: return@launch
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@launch
        when (val result = chatRepository.send(chat, ChatPayload.Text(body = trimmed), replyToId)) {
            is AppResult.Failure -> _error.value = result.error.message
            else -> _error.value = null
        }
    }

    fun sendSticker(emoji: String) = viewModelScope.launch {
        val chat = chat.value ?: return@launch
        chatRepository.send(chat, ChatPayload.Sticker(emoji))
    }

    fun sendVoiceNote(path: String, durationMs: Long) = viewModelScope.launch {
        val chat = chat.value ?: return@launch
        val bytes = java.io.File(path).readBytes()
        val (fileId, fileKey) = chatRepository.uploadEncryptedFile(bytes)
        chatRepository.send(
            chat,
            ChatPayload.Media(
                mediaType = "AUDIO",
                fileId = fileId,
                fileKey = fileKey,
                sizeBytes = bytes.size.toLong(),
                mimeType = "audio/mp4",
                durationMs = durationMs,
            ),
        )
    }

    fun sendFile(path: String, mimeType: String, displayName: String) = viewModelScope.launch {
        val chat = chat.value ?: return@launch
        val file = java.io.File(path)
        val bytes = file.readBytes()
        val (fileId, fileKey) = chatRepository.uploadEncryptedFile(bytes)
        val mediaType = when {
            mimeType.startsWith("image/") -> "IMAGE"
            mimeType.startsWith("video/") -> "VIDEO"
            else -> "FILE"
        }
        chatRepository.send(
            chat,
            ChatPayload.Media(
                mediaType = mediaType,
                fileId = fileId,
                fileKey = fileKey,
                fileName = displayName,
                sizeBytes = bytes.size.toLong(),
                mimeType = mimeType,
            ),
        )
    }

    fun sendLocation(lat: Double, lon: Double) = viewModelScope.launch {
        val chat = chat.value ?: return@launch
        chatRepository.send(chat, ChatPayload.Media(mediaType = "LOCATION", latitude = lat, longitude = lon))
    }

    fun sendContact(araId: String) = viewModelScope.launch {
        val chat = chat.value ?: return@launch
        chatRepository.send(chat, ChatPayload.Media(mediaType = "CONTACT", contactAraId = araId))
    }

    fun toggleReaction(messageId: String, emoji: String) = viewModelScope.launch {
        chatRepository.toggleReaction(chatId, messageId, emoji)
    }

    fun editMessage(messageId: String, newBody: String) = viewModelScope.launch {
        val chat = chat.value ?: return@launch
        chatRepository.editMessage(chat, messageId, newBody)
    }

    fun deleteMessage(messageId: String, forEveryone: Boolean) = viewModelScope.launch {
        val chat = chat.value ?: return@launch
        chatRepository.deleteMessage(chat, messageId, forEveryone)
    }

    fun toggleStar(messageId: String, starred: Boolean) = viewModelScope.launch {
        messageDao.setStarred(messageId, starred)
    }

    fun togglePinMessage(messageId: String, pinned: Boolean) = viewModelScope.launch {
        messageDao.setPinned(messageId, pinned)
    }

    fun forward(message: MessageEntity, targetChat: ChatEntity) = viewModelScope.launch {
        chatRepository.send(targetChat, ChatPayload.Text(body = message.body ?: ""), null)
    }

    fun setDisappearing(seconds: Long) = viewModelScope.launch {
        chatRepository.setDisappearing(chatId, seconds)
    }

    fun setSafeMode(enabled: Boolean) = viewModelScope.launch {
        chatRepository.setSafeMode(chatId, enabled)
    }

    fun notifyTyping() {
        if (typingJob?.isActive == true) return
        typingJob = viewModelScope.launch {
            chatRepository.sendTyping(chatId)
            delay(3000)
        }
    }

    fun attachmentFor(messageId: String, onLoaded: (AttachmentEntity?) -> Unit) = viewModelScope.launch {
        onLoaded(attachmentDao.forMessage(messageId).firstOrNull())
    }

    fun downloadAttachment(attachment: AttachmentEntity, onDone: (String?) -> Unit) = viewModelScope.launch {
        runCatching {
            val target = java.io.File(chatRepository.downloadDir(), attachment.remoteFileId ?: attachment.id)
            chatRepository.downloadEncryptedFile(
                fileId = attachment.remoteFileId ?: return@launch onDone(null),
                fileKeyB64 = attachment.fileKey ?: return@launch onDone(null),
                sizeBytes = attachment.sizeBytes,
                chunkSize = 256 * 1024,
                target = target,
            )
            attachmentDao.updateState(attachment.id, "DONE", target.absolutePath)
            onDone(target.absolutePath)
        }.onFailure { onDone(null) }
    }

    fun consumeError() { _error.value = null }
}
