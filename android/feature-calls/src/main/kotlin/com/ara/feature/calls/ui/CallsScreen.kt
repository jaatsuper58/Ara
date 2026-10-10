package com.ara.feature.calls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.ara.core.db.entity.CallLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** §7.5 — Calls tab: call log, tap-to-call, missed-call badges. */
@Composable
fun CallsScreen(
    onStartCall: (String, Boolean) -> Unit,
    viewModel: CallsViewModel = hiltViewModel(),
) {
    val log by viewModel.callLog.collectAsStateWithLifecycle()
    var showDial by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showDial = true }) {
                Icon(Icons.Default.Call, contentDescription = "New call")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text("Calls", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn {
                items(log, key = { it.id }) { entry ->
                    CallLogRow(entry, onClick = { onStartCall(entry.peerId, entry.isVideo) })
                }
            }
        }
    }

    if (showDial) {
        DialDialog(
            onDismiss = { showDial = false },
            onDial = { araId, video ->
                showDial = false
                onStartCall(araId, video)
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CallLogRow(entry: CallLogEntity, onClick: () -> Unit) {
    Surface(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = when (entry.direction) {
                "MISSED" -> Icons.Default.CallMissed
                "INCOMING" -> Icons.Default.CallReceived
                else -> Icons.Default.Call
            }
            Icon(icon, contentDescription = entry.direction.lowercase(), tint = if (entry.status == "MISSED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entry.peerId, style = MaterialTheme.typography.titleMedium)
                Text(
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(entry.startedAt)) +
                        if (entry.durationMs > 0) " · ${entry.durationMs / 60000}m" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.Videocam, contentDescription = if (entry.isVideo) "Video" else "Audio", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DialDialog(onDismiss: () -> Unit, onDial: (String, Boolean) -> Unit) {
    var araId by remember { mutableStateOf("") }
    var video by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call an Ara ID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = araId, onValueChange = { araId = it }, label = { Text("Ara ID") })
                TextButton(onClick = { video = !video }) {
                    Text(if (video) "Video call ✓" else "Audio call")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (araId.isNotBlank()) onDial(araId, video) }) { Text("Call") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
