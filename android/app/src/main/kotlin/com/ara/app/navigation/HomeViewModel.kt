package com.ara.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ara.core.common.AccountRepository
import com.ara.core.common.ChatIds
import com.ara.core.db.dao.CallLogDao
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.entity.ChatEntity
import com.ara.feature.calls.call.CallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-shell state: signed-in routing, missed-call badge, call placement. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val chatDao: ChatDao,
    private val callManager: CallManager,
    callLogDao: CallLogDao,
) : ViewModel() {

    val signedIn: StateFlow<Boolean?> = accountRepository.account
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val missedCalls: StateFlow<Int> = callLogDao.observeMissedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun placeCall(peerId: String, isVideo: Boolean) = callManager.placeCall(peerId, isVideo)

    fun openDirectChat(peerId: String, onReady: (String) -> Unit) = viewModelScope.launch {
        val me = accountRepository.current()?.araId ?: return@launch
        val chatId = ChatIds.direct(me, peerId)
        if (chatDao.byId(chatId) == null) {
            chatDao.upsert(ChatEntity(id = chatId, type = "DIRECT", title = peerId, peerId = peerId))
        }
        onReady(chatId)
    }
}
