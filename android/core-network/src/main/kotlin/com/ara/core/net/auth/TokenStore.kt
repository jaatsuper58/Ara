package com.ara.core.net.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ara.core.common.AraAccount
import com.ara.core.common.AccountRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.araDataStore by preferencesDataStore(name = "ara_session")

/** DataStore-backed session store. Tokens are transit credentials only — all durable data lives in the encrypted Room DB. */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccountRepository {

    private object Keys {
        val ARA_ID = stringPreferencesKey("ara_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val RECOVERY_EMAIL = stringPreferencesKey("recovery_email")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val PUSH_TOKEN = stringPreferencesKey("push_token")
    }

    override val account: Flow<AraAccount?> = context.araDataStore.data.map { prefs ->
        val araId = prefs[Keys.ARA_ID] ?: return@map null
        AraAccount(
            araId = araId,
            displayName = prefs[Keys.DISPLAY_NAME] ?: araId,
            recoveryEmail = prefs[Keys.RECOVERY_EMAIL],
            deviceId = prefs[Keys.DEVICE_ID] ?: newDeviceId().also {
                // Persist lazily; device id is created on first sign-in.
            },
        )
    }

    override suspend fun current(): AraAccount? = account.first()

    override suspend fun signIn(account: AraAccount, accessToken: String, refreshToken: String) {
        context.araDataStore.edit { prefs ->
            prefs[Keys.ARA_ID] = account.araId
            prefs[Keys.DISPLAY_NAME] = account.displayName
            prefs[Keys.RECOVERY_EMAIL] = account.recoveryEmail ?: ""
            prefs[Keys.DEVICE_ID] = prefs[Keys.DEVICE_ID] ?: account.deviceId.ifEmpty { newDeviceId() }
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
        }
    }

    override suspend fun signOut() {
        context.araDataStore.edit { it.clear() }
    }

    override suspend fun accessToken(): String? =
        context.araDataStore.data.first()[Keys.ACCESS_TOKEN]

    suspend fun refreshToken(): String? =
        context.araDataStore.data.first()[Keys.REFRESH_TOKEN]

    suspend fun updateTokens(accessToken: String, refreshToken: String) {
        context.araDataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
        }
    }

    suspend fun deviceId(): String {
        val existing = context.araDataStore.data.first()[Keys.DEVICE_ID]
        if (existing != null) return existing
        val id = newDeviceId()
        context.araDataStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    suspend fun pushToken(): String? = context.araDataStore.data.first()[Keys.PUSH_TOKEN]

    suspend fun updatePushToken(token: String) {
        context.araDataStore.edit { it[Keys.PUSH_TOKEN] = token }
    }

    private fun newDeviceId(): String = UUID.randomUUID().toString()
}
