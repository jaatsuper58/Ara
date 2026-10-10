package com.ara.feature.contacts.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ara.core.db.entity.ContactEntity
import com.ara.core.ui.components.Avatar

/**
 * §7.4 — Contacts: Ara ID search, QR scan-to-add, invite; no contact upload.
 */
@Composable
fun ContactsScreen(
    onOpenChat: (String, String) -> Unit,
    onScanQr: () -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    // §7.4 — QR scan-to-add (zxing-embedded). onScanQr remains for hosts that
    // want a custom scanner; the built-in ScanContract is wired by default.
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        result.contents?.let(viewModel::onScanResult)
    }
    val effectiveScan: () -> Unit = {
        val options = com.journeyapps.barcodescanner.ScanOptions()
            .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            .setPrompt("Scan an Ara QR code")
            .setBeepEnabled(true)
            .setOrientationLocked(true)
        scanLauncher.launch(options)
    }
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val myQr by viewModel.myQr.collectAsStateWithLifecycle()
    val addResult by viewModel.addResult.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add contact by Ara ID")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Contacts", style = MaterialTheme.typography.titleLarge)
                Row {
                    TextButton(onClick = { showQr = true }) { Text("My QR") }
                    TextButton(onClick = { effectiveScan() }) { Text("Scan") }
                }
            }

            addResult?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.araId }) { contact ->
                    ContactRow(
                        contact = contact,
                        onToggleFavorite = { viewModel.toggleFavorite(contact) },
                        onToggleBlock = { viewModel.toggleBlocked(contact) },
                        onMessage = { onOpenChat(contact.araId, contact.displayName) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddContactDialog(
            onDismiss = { showAdd = false; viewModel.consumeAddResult() },
            onAdd = { id, name -> viewModel.addContact(id, name) },
        )
    }

    if (showQr) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("My Ara ID") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    myQr?.let { bitmap ->
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "My QR code", modifier = Modifier.size(220.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Ask your contact to scan this, or share your Ara ID.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun ContactRow(
    contact: ContactEntity,
    onMessage: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBlock: () -> Unit,
) {
    Surface {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name = contact.displayName)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                Text(contact.araId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onMessage) { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Message", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onToggleBlock) { Icon(Icons.Default.Block, contentDescription = "Block", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add by Ara ID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Ara ID (e.g. ARA-XXXX-XXXX)") })
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (id.isNotBlank()) onAdd(id, name.ifBlank { null })
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
