package com.ara.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ara.core.common.ThemeMode
import com.ara.core.db.entity.ContactEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * §7.8 — Settings: profile, privacy, notifications, data & storage, theme,
 * security (app lock + safety-number check), about, local data export.
 */
@Composable
fun SettingsScreen(
    onOpenSafetyNumber: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val araId by viewModel.araId.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var passcodeDialog by remember { mutableStateOf(false) }
    var passcode by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewModel.exportData(
                provide = {
                    val json = buildJsonObject {
                        put("araId", araId)
                        put("exportedAt", System.currentTimeMillis())
                        put("note", "Local export. Messages are stored only on this device.")
                    }
                    Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), json)
                },
                onResult = { content ->
                    if (content != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(content.toByteArray())
                        }
                    }
                },
            )
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            Text("Profile", style = MaterialTheme.typography.titleMedium)
            Text("Ara ID: $araId", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Read receipts", "Show ✓✓ when you've read messages", prefs.readReceipts, viewModel::setReadReceipts)
            ToggleRow("Last seen & online", "Let contacts see your status", prefs.statusVisible, viewModel::setStatusVisibility)
            ToggleRow("Typing indicators", "Show when you're typing", prefs.typingIndicators, viewModel::setTypingIndicators)
            TextButton(onClick = onOpenSafetyNumber) { Text("Safety number verification") }
            if (blocked.isNotEmpty()) {
                BlockedSection(blocked, onUnblock = viewModel::unblock)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Data & storage", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Auto-download on Wi-Fi", checked = prefs.autoDownloadWifi, onChange = viewModel::setAutoDownloadWifi)
            ToggleRow("Auto-download on mobile data", checked = prefs.autoDownloadMobile, onChange = viewModel::setAutoDownloadMobile)
            TextButton(onClick = { exportLauncher.launch("ara-export.json") }) { Text("Export my data (local JSON)") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Security", style = MaterialTheme.typography.titleMedium)
            ToggleRow("App lock", "Require unlock to open Ara", prefs.appLockEnabled) { enabled ->
                viewModel.setAppLock(enabled)
                if (enabled) passcodeDialog = true
            }
            TextButton(onClick = { passcodeDialog = true }) { Text("Change passcode") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf(ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.SYSTEM to "System").forEach { (mode, label) ->
                    TextButton(onClick = { viewModel.setTheme(mode) }) {
                        Text(if (prefs.theme == mode) "● $label" else label)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ara — private, end-to-end encrypted messenger. No ads, no trackers, " +
                    "no contact upload. Message content never touches Ara servers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { viewModel.signOut(onSignOut) }) { Text("Sign out") }
        }
    }

    if (passcodeDialog) {
        AlertDialog(
            onDismissRequest = { passcodeDialog = false },
            title = { Text("Set passcode") },
            text = {
                OutlinedTextField(
                    value = passcode,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) passcode = it },
                    label = { Text("4–6 digit passcode") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (passcode.length >= 4) viewModel.setPasscode(passcode)
                    passcode = ""
                    passcodeDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.setPasscode(null)
                    passcodeDialog = false
                }) { Text("Remove passcode") }
            },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BlockedSection(blocked: List<ContactEntity>, onUnblock: (String) -> Unit) {
    Text("Blocked", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    blocked.forEach { contact ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(contact.displayName, modifier = Modifier.weight(1f))
            TextButton(onClick = { onUnblock(contact.araId) }) { Text("Unblock") }
        }
    }
}
