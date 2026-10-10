package com.ara.feature.settings.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ara.core.crypto.session.MessageCrypto
import com.ara.core.db.dao.ContactDao
import com.ara.core.db.entity.ContactEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Safety-number verification (§4/§9): compare fingerprints to rule out MITM. */
@HiltViewModel
class SafetyNumberViewModel @Inject constructor(
    private val crypto: MessageCrypto,
    contactDao: ContactDao,
) : ViewModel() {
    val contacts: StateFlow<List<ContactEntity>> = contactDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    private val _number = MutableStateFlow<String?>(null)
    val number: StateFlow<String?> = _number

    private val _verified = MutableStateFlow(false)
    val verified: StateFlow<Boolean> = _verified

    fun select(araId: String) = viewModelScope.launch {
        _selected.value = araId
        runCatching { _number.value = crypto.safetyNumberWith(araId) }
            .onFailure { _number.value = null }
        _verified.value = crypto.remoteIdentity(araId)?.verified == true
    }

    fun toggleVerified() = viewModelScope.launch {
        val id = _selected.value ?: return@launch
        val next = !_verified.value
        crypto.markIdentityVerified(id, next)
        _verified.value = next
    }
}

@Composable
fun SafetyNumberScreen(onBack: () -> Unit, viewModel: SafetyNumberViewModel = hiltViewModel()) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val number by viewModel.number.collectAsStateWithLifecycle()
    val verified by viewModel.verified.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(selected == null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Safety number") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )

        if (picking) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                items(contacts, key = { it.araId }) { contact ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(contact.displayName, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = {
                            viewModel.select(contact.araId)
                            picking = false
                        }) { Text("Select") }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(selected ?: "", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    number ?: "Unavailable — exchange a message first so identities are on record.",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "Compare this 60-digit number with your contact over another channel. " +
                        "If they match, there is no man-in-the-middle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                if (verified) {
                    Button(onClick = viewModel::toggleVerified) { Text("Mark as not verified") }
                } else {
                    OutlinedButton(onClick = viewModel::toggleVerified, enabled = number != null) {
                        Text("Mark verified")
                    }
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = { picking = true }) { Text("Choose another contact") }
            }
        }
    }
}
