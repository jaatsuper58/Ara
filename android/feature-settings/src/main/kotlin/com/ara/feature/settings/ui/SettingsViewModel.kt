package com.ara.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ara.core.common.AccountRepository
import com.ara.core.common.ThemeMode
import com.ara.core.common.UserPrefs
import com.ara.core.common.UserPrefsRepository
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepository: UserPrefsRepository,
    private val accountRepository: AccountRepository,
    private val contactDao: ContactDao,
    private val crypto: MessageCrypto,
) : ViewModel() {

    val prefs: StateFlow<UserPrefs> = prefsRepository.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPrefs())

    private val _araId = MutableStateFlow("")
    val araId: StateFlow<String> = _araId

    private val _blocked = MutableStateFlow<List<ContactEntity>>(emptyList())
    val blocked: StateFlow<List<ContactEntity>> = _blocked

    private val _safetyNumber = MutableStateFlow<String?>(null)
    val safetyNumber: StateFlow<String?> = _safetyNumber

    init {
        viewModelScope.launch { _araId.value = accountRepository.current()?.araId ?: "" }
        viewModelScope.launch { _blocked.value = contactDao.blocked() }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefsRepository.setTheme(mode) }
    fun setReadReceipts(enabled: Boolean) = viewModelScope.launch { prefsRepository.setReadReceipts(enabled) }
    fun setStatusVisibility(visible: Boolean) = viewModelScope.launch { prefsRepository.setStatusVisibility(visible) }
    fun setAppLock(enabled: Boolean) = viewModelScope.launch { prefsRepository.setAppLock(enabled) }
    fun setAutoDownloadWifi(enabled: Boolean) = viewModelScope.launch { prefsRepository.setAutoDownloadWifi(enabled) }
    fun setAutoDownloadMobile(enabled: Boolean) = viewModelScope.launch { prefsRepository.setAutoDownloadMobile(enabled) }
    fun setTypingIndicators(enabled: Boolean) = viewModelScope.launch { prefsRepository.setTypingIndicators(enabled) }

    fun setPasscode(passcode: String?) = viewModelScope.launch {
        val hash = passcode?.let { pc ->
            // Local PBKDF2 via the AppLockManager helper (injected in screen layer for prompt UX).
            com.ara.feature.settings.security.AppLockManagerHash.hash(pc)
        }
        prefsRepository.setPasscodeHash(hash)
        if (passcode != null) prefsRepository.setAppLock(true)
    }

    fun loadSafetyNumber(peerId: String) = viewModelScope.launch {
        runCatching { _safetyNumber.value = crypto.safetyNumberWith(peerId) }
            .onFailure { _safetyNumber.value = null }
    }

    fun unblock(araId: String) = viewModelScope.launch {
        contactDao.setBlocked(araId, false)
        _blocked.value = contactDao.blocked()
    }

    fun signOut(onDone: () -> Unit) = viewModelScope.launch {
        accountRepository.signOut()
        onDone()
    }

    /** Local data export: everything already lives on-device; dump a JSON archive. */
    fun exportData(provide: suspend () -> String, onResult: (String?) -> Unit) = viewModelScope.launch {
        onResult(runCatching { provide() }.getOrNull())
    }
}

/** Pure helper so screens can hash passcodes without needing the activity-bound manager. */
object AppLockManagerHash {
    fun hash(passcode: String): String {
        val salt = com.ara.core.crypto.primitives.Crypto.randomBytes(16)
        val spec = javax.crypto.spec.PBEKeySpec(passcode.toCharArray(), salt, 150_000, 256)
        val hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return salt.joinToString("") { "%02x".format(it) } + ":" + hash.joinToString("") { "%02x".format(it) }
    }

    fun verify(passcode: String, stored: String): Boolean {
        val salt = stored.substringBefore(":").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = javax.crypto.spec.PBEKeySpec(passcode.toCharArray(), salt, 150_000, 256)
        val hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return (salt.joinToString("") { "%02x".format(it) } + ":" + hash.joinToString("") { "%02x".format(it) }) == stored
    }
}
