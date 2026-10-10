package com.ara.feature.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ara.core.common.ChatIds
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.dao.ContactDao
import com.ara.core.db.entity.ChatEntity
import com.ara.feature.chat.data.ChatRepository
import com.ara.feature.chat.data.GroupsRepository
import com.ara.core.net.ws.SignalingClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatDao: ChatDao,
    private val chatRepository: ChatRepository,
    private val groupsRepository: GroupsRepository,
    private val contactDao: ContactDao,
    private val signaling: SignalingClient,
) : ViewModel() {

    val chats: StateFlow<List<ChatEntity>> = chatDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _groupError = MutableStateFlow<String?>(null)
    val groupError: StateFlow<String?> = _groupError

    init {
        viewModelScope.launch {
            // Bootstrap the notes-to-self thread and bring the socket up.
            chatRepository.ensureSavedMessages()
            signaling.connect()
        }
    }

    /** Creates a group with the given members; onReady receives the local group chat id. */
    fun createGroup(name: String, memberIds: List<String>, onReady: (String) -> Unit) = viewModelScope.launch {
        when (val result = groupsRepository.createGroup(name, memberIds)) {
            is com.ara.core.common.AppResult.Success -> {
                _groupError.value = null
                onReady(result.value)
            }
            is com.ara.core.common.AppResult.Failure -> _groupError.value = result.error.message
        }
    }

    fun togglePin(chat: ChatEntity) = viewModelScope.launch {
        chatDao.setPinned(chat.id, !chat.isPinned)
    }

    fun toggleMute(chat: ChatEntity) = viewModelScope.launch {
        chatDao.setMuted(chat.id, !chat.isMuted)
    }

    fun deleteChat(chat: ChatEntity) = viewModelScope.launch {
        chatDao.delete(chat.id)
    }
}
