package com.ara.feature.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ara.core.db.entity.ChatEntity
import com.ara.core.ui.components.Avatar
import com.ara.core.ui.components.UnreadBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** §7.2 — Chat list: pinned chats on top, unread badges, long-press swipe-style actions. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpenChat: (ChatEntity) -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val groupError by viewModel.groupError.collectAsStateWithLifecycle()
    var showNewGroup by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewGroup = true }) {
                Icon(Icons.Default.GroupAdd, contentDescription = "New group")
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
            if (chats.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Welcome to Ara", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.padding(8.dp))
                    Text(
                        "Add a contact by Ara ID or QR code to start an encrypted chat.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groupError?.let {
                        item {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                    items(chats, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            onClick = { onOpenChat(chat) },
                            onPin = { viewModel.togglePin(chat) },
                            onMute = { viewModel.toggleMute(chat) },
                            onDelete = { viewModel.deleteChat(chat) },
                        )
                    }
                }
            }
        }
    }

    if (showNewGroup) {
        NewGroupDialog(
            onDismiss = { showNewGroup = false },
            onCreate = { name, members ->
                viewModel.createGroup(name, members) { chatId ->
                    showNewGroup = false
                    onOpenChat(
                        ChatEntity(id = chatId, type = "GROUP", title = name.ifBlank { "Group" })
                    )
                }
            },
        )
    }
}

@Composable
private fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Group name") })
                OutlinedTextField(
                    value = members,
                    onValueChange = { members = it },
                    label = { Text("Member Ara IDs (comma-separated)") },
                )
                Text(
                    "Up to ${com.ara.core.common.Constants.GROUP_MEMBER_LIMIT} members. Group messages use sender-key encryption.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    name,
                    members.split(",", ";").map { it.trim() }.filter { it.isNotBlank() },
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatEntity,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Surface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name = chat.title)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.isPinned) {
                        Icon(Icons.Default.PushPin, contentDescription = "Pinned", modifier = Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(chat.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        text = chat.lastMessageAt.takeIf { it > 0 }?.let {
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.lastMessagePreview ?: "No messages yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (chat.isMuted) {
                        Icon(Icons.Default.NotificationsOff, contentDescription = "Muted", modifier = Modifier.padding(end = 4.dp))
                    }
                    UnreadBadge(chat.unreadCount)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(if (chat.isPinned) "Unpin" else "Pin") }, onClick = { onPin(); menuOpen = false }, leadingIcon = { Icon(Icons.Default.PushPin, null) })
                DropdownMenuItem(text = { Text(if (chat.isMuted) "Unmute" else "Mute") }, onClick = { onMute(); menuOpen = false }, leadingIcon = { Icon(Icons.Default.NotificationsOff, null) })
                DropdownMenuItem(text = { Text("Archive") }, onClick = { menuOpen = false }, leadingIcon = { Icon(Icons.Default.Archive, null) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Default.Delete, null) })
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete chat?") },
            text = { Text("This removes the conversation from this device. Server stores no message content.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); confirmDelete = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
