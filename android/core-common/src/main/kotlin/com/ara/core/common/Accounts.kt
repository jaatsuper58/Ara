package com.ara.core.common

import kotlinx.coroutines.flow.Flow

/** User-adjustable preferences, backed by DataStore on device. */
interface UserPrefsRepository {
    val prefs: Flow<UserPrefs>
    suspend fun setTheme(mode: ThemeMode)
    suspend fun setReadReceipts(enabled: Boolean)
    suspend fun setStatusVisibility(visible: Boolean)
    suspend fun setAppLock(enabled: Boolean)
    suspend fun setPasscodeHash(hash: String?)
    suspend fun setAutoDownloadWifi(enabled: Boolean)
    suspend fun setAutoDownloadMobile(enabled: Boolean)
    suspend fun setTypingIndicators(enabled: Boolean)
}

data class UserPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val readReceipts: Boolean = true,
    val statusVisible: Boolean = true,
    val appLockEnabled: Boolean = false,
    val passcodeHash: String? = null,
    val autoDownloadWifi: Boolean = true,
    val autoDownloadMobile: Boolean = false,
    val typingIndicators: Boolean = true,
)

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Account/session state for the signed-in user. */
interface AccountRepository {
    val account: Flow<AraAccount?>
    suspend fun current(): AraAccount?
    suspend fun signIn(account: AraAccount, accessToken: String, refreshToken: String)
    suspend fun signOut()
    suspend fun accessToken(): String?
}

data class AraAccount(
    val araId: String,
    val displayName: String,
    val recoveryEmail: String? = null,
    val deviceId: String,
)
