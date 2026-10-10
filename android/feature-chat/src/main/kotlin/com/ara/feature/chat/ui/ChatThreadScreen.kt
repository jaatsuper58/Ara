package com.ara.feature.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.MessageEntity
import com.ara.core.ui.components.Avatar
import com.ara.core.ui.theme.outgoingBubbleColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.TextButton

/**
 * §7.3 — Chat thread: bubbles, reply/reaction overlay, attachment picker,
 * hold-to-record voice note, disappearing-timer banner, safe-mode toggle.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(
    onBack: () -> Unit,
    onStartCall: (ChatEntity, Boolean) -> Unit,
    onOpenGroup: (String) -> Unit = {},
    viewModel: ChatThreadViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val typing by viewModel.typing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var actionFor by remember { mutableStateOf<MessageEntity?>(null) }
    var attachSheet by remember { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    var forwardFor by remember { mutableStateOf<MessageEntity?>(null) }
    var stickerSheet by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) } // path to mime

    val recorder = remember {
        VoiceNoteRecorder(context, java.io.File(context.cacheDir, "voice"))
    }
    var recording by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                val name = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }?.let { cursor.getString(it) } ?: "file"
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val cached = java.io.File(context.cacheDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cached.outputStream().use { input.copyTo(it) }
                }
                viewModel.sendFile(cached.absolutePath, mime, name)
            }
        }
    }

    LaunchedEffect(error) {
        error?.let {
            // Surface pipeline failures as a transient snackbar text; auto-clear.
            kotlinx.coroutines.delay(2500)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat?.title ?: "Chat", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when {
                                typing -> "typing…"
                                chat?.disappearingSeconds?.let { it > 0 } == true ->
                                    "disappearing: ${describeTimer(chat!!.disappearingSeconds)}"
                                else -> chat?.type?.lowercase() ?: ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { chat?.let { onStartCall(it, false) } }) {
                        Icon(Icons.Default.Phone, contentDescription = "Voice call")
                    }
                    IconButton(onClick = { chat?.let { onStartCall(it, true) } }) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video call")
                    }
                    IconButton(onClick = { topMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Chat options")
                    }
                    DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Disappearing: ${describeTimer(chat?.disappearingSeconds ?: 0)}") },
                            onClick = {},
                            enabled = false,
                        )
                        listOf(0L to "Off", 3600L to "1 hour", 86400L to "1 day", 604800L to "1 week").forEach { (secs, label) ->
                            DropdownMenuItem(
                                text = { Text("  $label") },
                                onClick = { viewModel.setDisappearing(secs); topMenu = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (chat?.safeMode == true) "Disable safe mode" else "Enable safe mode") },
                            onClick = { viewModel.setSafeMode(chat?.safeMode != true); topMenu = false },
                        )
                        if (chat?.type == "GROUP") {
                            DropdownMenuItem(
                                text = { Text("Group info") },
                                onClick = { topMenu = false; onOpenGroup(chat?.id ?: return@DropdownMenuItem) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = rememberLazyListState(),
                reverseLayout = false,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isMine = message.senderId == viewModel.myId,
                        attachments = attachments[message.id].orEmpty(),
                        onOpenMedia = { path, mime -> viewing = path to mime },
                        onDownload = { att -> viewModel.downloadAttachment(att) {} },
                        onLongPress = { actionFor = message },
                    )
                }
            }

            if (replyTo != null) {
                ReplyBar(replyTo!!, onDismiss = { replyTo = null })
            }
            if (editing != null) {
                ReplyBar(editing!!, label = "Editing", onDismiss = { editing = null })
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            InputBar(
                value = input,
                onValueChange = {
                    input = it
                    viewModel.notifyTyping()
                },
                recording = recording,
                onSend = {
                    if (editing != null) {
                        viewModel.editMessage(editing!!.id, input.trim())
                        editing = null
                    } else {
                        viewModel.send(input, replyTo?.id)
                        replyTo = null
                    }
                    input = ""
                },
                onAttach = { attachSheet = true },
                onRecordStart = {
                    recording = recorder.start()
                },
                onRecordEnd = { send ->
                    recording = false
                    val file = if (send) recorder.stop() else recorder.cancel()
                    if (send && file != null) {
                        viewModel.sendVoiceNote(file.absolutePath, durationMs = 0)
                    }
                },
            )

            if (attachSheet) {
                AttachmentSheet(
                    onDismiss = { attachSheet = false },
                    onPickFile = { attachSheet = false; filePicker.launch("*/*") },
                    onPickStickers = { attachSheet = false; stickerSheet = true },
                    onSendLocation = { attachSheet = false; viewModel.sendLocation(0.0, 0.0) },
                    onSendContact = { attachSheet = false; viewModel.sendContact(chat?.peerId ?: "") },
                )
            }
        }
    }

    actionFor?.let { message ->
        MessageActionsSheet(
            message = message,
            isMine = message.senderId == viewModel.myId,
            onDismiss = { actionFor = null },
            onReply = { replyTo = message; actionFor = null },
            onEdit = { editing = message; input = message.body ?: ""; actionFor = null },
            onForward = { forwardFor = message; actionFor = null },
            onStar = { viewModel.toggleStar(message.id, !message.starred); actionFor = null },
            onPin = { viewModel.togglePinMessage(message.id, !message.pinned); actionFor = null },
            onDelete = { forEveryone -> viewModel.deleteMessage(message.id, forEveryone); actionFor = null },
            onReact = { emoji -> viewModel.toggleReaction(message.id, emoji); actionFor = null },
        )
    }
    if (forwardFor != null) {
        ForwardDialog(
            onDismiss = { forwardFor = null },
            viewModel = viewModel,
            message = forwardFor!!,
        )
    }
    if (stickerSheet) {
        StickerSheet(onDismiss = { stickerSheet = false }, onPick = { emoji ->
            stickerSheet = false
            viewModel.sendSticker(emoji)
        })
    }
    viewing?.let { (path, mime) ->
        MediaViewerDialog(path = path, mime = mime, onDismiss = { viewing = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    isMine: Boolean,
    attachments: List<com.ara.core.db.entity.AttachmentEntity>,
    onOpenMedia: (path: String, mime: String) -> Unit,
    onDownload: (com.ara.core.db.entity.AttachmentEntity) -> Unit,
    onLongPress: () -> Unit,
) {
    val bubbleColor = if (isMine) outgoingBubbleColor() else MaterialTheme.colorScheme.surfaceVariant
    val alignment = if (isMine) Alignment.End else Alignment.Start
    val attachment = attachments.firstOrNull()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalAlignment = alignment) {
        Surface(color = bubbleColor, shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        onClick = {
                            val local = attachment?.localPath
                            if (local != null) onOpenMedia(local, attachment.mimeType)
                        },
                        onLongClick = onLongPress,
                    )
                    .padding(10.dp),
            ) {
                if (message.deletedForEveryone) {
                    Text("This message was deleted", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    when (message.type) {
                        "STICKER" -> Text(message.body ?: " stickers", fontSize = 44.sp)
                        "IMAGE" -> MediaBubbleImage(attachment, onDownload)
                        "VIDEO" -> MediaBubbleImage(attachment, onDownload, label = "🎥 Video")
                        "AUDIO" -> MediaBubbleAudio(attachment, onDownload)
                        "LOCATION" -> Text("📍 Location shared", style = MaterialTheme.typography.bodyMedium)
                        "CONTACT" -> Text("👤 Contact card", style = MaterialTheme.typography.bodyMedium)
                        "FILE" -> Text("📄 ${attachment?.remoteFileId?.takeLast(12) ?: "Document"}", style = MaterialTheme.typography.bodyMedium)
                        else -> Text(message.body ?: "", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (message.starred) Text("★ ", style = MaterialTheme.typography.labelSmall)
            if (message.pinned) Text("📌 ", style = MaterialTheme.typography.labelSmall)
            if (message.editedAt != null) Text("edited ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isMine) Text(statusTick(message.status), style = MaterialTheme.typography.labelSmall)
        }
        MessageReactionsRow(message)
    }
}

private fun statusTick(status: String): String = when (status) {
    "PENDING" -> " ∙"
    "SENT" -> " ✓"
    "DELIVERED" -> " ✓✓"
    "READ" -> " ✓✓"
    "FAILED" -> " !"
    else -> ""
}

@Composable
private fun MessageReactionsRow(message: MessageEntity) {
    val reactions = com.ara.feature.chat.data.MessageReactions.decode(message.reactionsJson)
    if (reactions.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        reactions.forEach { (emoji, users) ->
            AssistChip(onClick = {}, label = { Text("$emoji ${users.size}") })
        }
    }
}

@Composable
private fun ReplyBar(message: MessageEntity, label: String = "Replying to", onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(message.body ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    recording: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRecordStart: () -> Unit,
    onRecordEnd: (send: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAttach) {
            Icon(Icons.Default.Add, contentDescription = "Attach")
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (recording) "Recording…" else "Message") },
            maxLines = 4,
        )
        if (value.isBlank()) {
            val pressModifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onRecordStart()
                        try {
                            awaitRelease()
                        } finally {
                            // Release without sending on tap; long-press release sends.
                            onRecordEnd(true)
                        }
                    },
                )
            }
            IconButton(onClick = {}, modifier = pressModifier) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = if (recording) "Recording — release to send" else "Hold to record voice note",
                    tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            IconButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageActionsSheet(
    message: MessageEntity,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onStar: () -> Unit,
    onPin: () -> Unit,
    onDelete: (forEveryone: Boolean) -> Unit,
    onReact: (String) -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                Text(emoji, modifier = Modifier.combinedClickable(onClick = { onReact(emoji) }, onLongClick = {}).padding(4.dp))
            }
        }
        DropdownMenuItem(text = { Text("Reply") }, onClick = onReply)
        DropdownMenuItem(text = { Text("Forward") }, onClick = onForward)
        DropdownMenuItem(text = { Text(if (message.starred) "Unstar" else "Star") }, onClick = onStar)
        DropdownMenuItem(text = { Text(if (message.pinned) "Unpin" else "Pin") }, onClick = onPin)
        if (message.type == "TEXT" && isMine && !message.deletedForEveryone) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = onEdit)
        }
        DropdownMenuItem(text = { Text("Copy") }, onClick = onDismiss)
        DropdownMenuItem(text = { Text("Delete for me") }, onClick = { onDelete(false) })
        DropdownMenuItem(text = { Text("Delete for everyone") }, onClick = { onDelete(true) })
        androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onPickStickers: () -> Unit,
    onSendLocation: () -> Unit,
    onSendContact: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Attach", style = MaterialTheme.typography.titleMedium)
            DropdownMenuItem(text = { Text("🎨 Stickers & GIFs") }, onClick = onPickStickers)
            DropdownMenuItem(text = { Text("📄 Document or media") }, onClick = onPickFile)
            DropdownMenuItem(text = { Text("📍 Location") }, onClick = onSendLocation)
            DropdownMenuItem(text = { Text("👤 Contact") }, onClick = onSendContact)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ForwardDialog(
    message: MessageEntity,
    viewModel: ChatThreadViewModel,
    onDismiss: () -> Unit,
) {
    val chats by viewModel.allChats.collectAsStateWithLifecycle()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to…") },
        text = {
            LazyColumn(modifier = Modifier.widthIn(max = 320.dp)) {
                items(chats) { chat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = {
                                viewModel.forward(message, chat)
                                onDismiss()
                            })
                            .padding(vertical = 8.dp),
                    ) {
                        Avatar(name = chat.title, size = 36.dp)
                        Text(chat.title, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Photo/video bubble body: downloaded media renders inline, remote offers one-tap fetch. */
@Composable
private fun MediaBubbleImage(
    attachment: com.ara.core.db.entity.AttachmentEntity?,
    onDownload: (com.ara.core.db.entity.AttachmentEntity) -> Unit,
    label: String = "📷 Photo",
) {
    val local = attachment?.localPath
    if (local != null && java.io.File(local).exists()) {
        coil.compose.AsyncImage(
            model = java.io.File(local),
            contentDescription = label,
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            contentScale = ContentScale.Crop,
        )
    } else if (attachment != null) {
        TextButton(onClick = { onDownload(attachment) }) { Text("⬇ $label") }
    } else {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MediaBubbleAudio(
    attachment: com.ara.core.db.entity.AttachmentEntity?,
    onDownload: (com.ara.core.db.entity.AttachmentEntity) -> Unit,
) {
    if (attachment != null) {
        TextButton(onClick = { onDownload(attachment) }) { Text("🎙 Voice note · tap to load") }
    } else {
        Text("🎙 Voice note", style = MaterialTheme.typography.bodyMedium)
    }
}

/** §2 Stickers — local emoji sticker pack; GIFs ride the normal file path. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        val stickers = remember {
            listOf(
                "😀", "😂", "🥹", "😍", "🤩", "😎", "🤔", "😴", "🥳", "😭", "😡", "🤯",
                "👍", "🙏", "👏", "💪", "🤝", "🫡", "🔥", "✨", "🎉", "❤️", "💔", "🚀",
                "🌟", "🍕", "☕", "🏆", "🎯", "💡", "🦄", "🐱", "🐶", "🌈", "🌊", "🌸",
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            gridItems(stickers) { sticker ->
                Text(
                    sticker,
                    fontSize = 34.sp,
                    modifier = Modifier.clickable { onPick(sticker) }.padding(4.dp),
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
    }
}

/** §2 In-chat media viewer: fullscreen image or Media3 playback. */
@Composable
private fun MediaViewerDialog(path: String, mime: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (mime.startsWith("image/") || mime == "IMAGE") {
                coil.compose.AsyncImage(
                    model = java.io.File(path),
                    contentDescription = "Media viewer",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                val player = remember(path) {
                    androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                        setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path))))
                        prepare()
                        playWhenReady = true
                    }
                }
                AndroidView(
                    factory = { ctx -> androidx.media3.ui.PlayerView(ctx).apply { this.player = player } },
                    modifier = Modifier.fillMaxSize(),
                )
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { player.release() }
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) { Text("✕", color = Color.White) }
        }
    }
}

private fun describeTimer(seconds: Long): String = when (seconds) {
    0L -> "off"
    3600L -> "1h"
    86400L -> "1d"
    604800L -> "1w"
    else -> "${seconds}s"
}
