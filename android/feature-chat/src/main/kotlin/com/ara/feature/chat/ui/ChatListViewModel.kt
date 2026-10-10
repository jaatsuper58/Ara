package com.ara.feature.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ara.core.common.AccountRepository
import com.ara.core.common.ChatIds
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.dao.ContactDao
import com.ara.core.db.entity.ChatEntity
import com.ara.feature.chat.data.ChatRepository
import com.ara.core.net.ws.SignalingClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatDao: ChatDao,
    private val chatRepository: ChatRepository,
    private val contactDao: ContactDao,
    private val accountRepository: AccountRepository,
    private val signaling: SignalingClient,
) : ViewModel() {

    val chats: StateFlow<List<ChatEntity>> = chatDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // Bootstrap the notes-to-self thread and bring the socket up.
            chatRepository.ensureSavedMessages()
            signaling.connect()
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
