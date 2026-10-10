package com.ara.feature.settings.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ara.core.common.ThemeMode
import com.ara.core.common.UserPrefs
import com.ara.core.common.UserPrefsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "ara_settings")

@Singleton
class UserPrefsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UserPrefsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val READ_RECEIPTS = booleanPreferencesKey("read_receipts")
        val STATUS_VISIBLE = booleanPreferencesKey("status_visible")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val PASSCODE_HASH = stringPreferencesKey("passcode_hash")
        val AUTO_WIFI = booleanPreferencesKey("auto_wifi")
        val AUTO_MOBILE = booleanPreferencesKey("auto_mobile")
        val TYPING = booleanPreferencesKey("typing")
    }

    override val prefs: Flow<UserPrefs> = context.settingsDataStore.data.map { p ->
        UserPrefs(
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            readReceipts = p[Keys.READ_RECEIPTS] ?: true,
            statusVisible = p[Keys.STATUS_VISIBLE] ?: true,
            appLockEnabled = p[Keys.APP_LOCK] ?: false,
            passcodeHash = p[Keys.PASSCODE_HASH],
            autoDownloadWifi = p[Keys.AUTO_WIFI] ?: true,
            autoDownloadMobile = p[Keys.AUTO_MOBILE] ?: false,
            typingIndicators = p[Keys.TYPING] ?: true,
        )
    }

    override suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    override suspend fun setReadReceipts(enabled: Boolean) = edit { it[Keys.READ_RECEIPTS] = enabled }
    override suspend fun setStatusVisibility(visible: Boolean) = edit { it[Keys.STATUS_VISIBLE] = visible }
    override suspend fun setAppLock(enabled: Boolean) = edit { it[Keys.APP_LOCK] = enabled }
    override suspend fun setPasscodeHash(hash: String?) = edit { prefs ->
        if (hash == null) prefs.remove(Keys.PASSCODE_HASH) else prefs[Keys.PASSCODE_HASH] = hash
    }
    override suspend fun setAutoDownloadWifi(enabled: Boolean) = edit { it[Keys.AUTO_WIFI] = enabled }
    override suspend fun setAutoDownloadMobile(enabled: Boolean) = edit { it[Keys.AUTO_MOBILE] = enabled }
    override suspend fun setTypingIndicators(enabled: Boolean) = edit { it[Keys.TYPING] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) {
        context.settingsDataStore.edit(block)
    }
}
