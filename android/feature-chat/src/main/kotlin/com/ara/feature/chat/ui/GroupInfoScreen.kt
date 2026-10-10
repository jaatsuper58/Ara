package com.ara.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.GroupMemberEntity
import com.ara.feature.chat.data.GroupsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    private val chatDao: ChatDao,
    private val groupsRepository: GroupsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val chatId: String = savedStateHandle.get<String>("chatId") ?: ""

    val chat: StateFlow<ChatEntity?> = chatDao.observeById(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val members: StateFlow<List<GroupMemberEntity>> = chatDao.observeMembers(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch { groupsRepository.refresh(chatId) }
    }

    fun addMember(araId: String) = viewModelScope.launch {
        when (val r = groupsRepository.addMember(chatId, araId)) {
            is com.ara.core.common.AppResult.Failure -> _error.value = r.error.message
            else -> _error.value = null
        }
    }

    fun removeMember(araId: String) = viewModelScope.launch {
        groupsRepository.removeMember(chatId, araId)
    }

    fun leave(onDone: () -> Unit) = viewModelScope.launch {
        groupsRepository.leave(chatId)
        onDone()
    }

    fun consumeError() { _error.value = null }
}

/** §7.7 — Group info: members, roles, add/remove, shared settings, leave. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    onBack: () -> Unit,
    onLeft: () -> Unit,
    viewModel: GroupInfoViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<GroupMemberEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(chat?.title ?: "Group", style = MaterialTheme.typography.titleMedium)
                    Text("${members.size} members · ${chat?.type?.lowercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add member")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(members, key = { it.araId }) { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.ara.core.ui.components.Avatar(name = member.displayName, size = 40.dp)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(member.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                member.role.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (member.role == "OWNER") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (member.role != "OWNER") {
                            IconButton(onClick = { removing = member }) {
                                Icon(Icons.Default.PersonRemove, contentDescription = "Remove ${member.displayName}")
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { confirmLeave = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text("Leave group", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    if (showAdd) {
        var id by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add member") },
            text = {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Ara ID") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (id.isNotBlank()) viewModel.addMember(id.trim())
                    id = ""
                    showAdd = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }

    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${member.displayName}?") },
            text = { Text("They will no longer receive group messages.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeMember(member.araId); removing = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave group?") },
            text = { Text("You will stop receiving messages from this group on this device.") },
            confirmButton = {
                TextButton(onClick = { viewModel.leave(onLeft); confirmLeave = false }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}
