package com.ara.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ara.core.common.AccountRepository
import com.ara.core.common.AppError
import com.ara.core.common.AppResult
import com.ara.core.crypto.keystore.AraKeyManager
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.entity.ChatEntity
import com.ara.core.net.api.AuthApi
import com.ara.core.net.auth.TokenStore
import com.ara.core.net.dto.CreateAccountRequest
import com.ara.core.net.dto.DeviceDto
import com.ara.core.net.dto.SessionRequest
import com.ara.core.net.ws.SignalingClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import com.ara.core.common.ChatIds
import com.ara.core.crypto.keys.KeyBundle

/**
 * §7.1 — Onboarding: phone-number-free signup. A unique Ara ID is generated on
 * first launch together with the device's X3DH identity keypair; the public
 * bundle is uploaded. No SIM, no contact upload.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val accountRepository: AccountRepository,
    private val keyManager: AraKeyManager,
    private val chatDao: ChatDao,
    private val signaling: SignalingClient,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _createdAraId = MutableStateFlow<String?>(null)
    val createdAraId: StateFlow<String?> = _createdAraId

    /** Primary path: create account with a generated Ara ID + optional recovery email. */
    fun createAccount(recoveryEmail: String?) = viewModelScope.launch {
        _busy.value = true
        _error.value = null
        try {
            val deviceId = tokenStore.deviceId()
            val araId = "ARA-" + randomBlock() + "-" + randomBlock()
            // 1. Generate + persist the identity keys (private halves never leave the device).
            val identity = keyManager.identity()
            val spk = keyManager.currentSignedPreKey()
            val opks = keyManager.addOneTimePreKeys(100)
            val bundle = KeyBundle(
                araId = araId,
                identityX25519 = identity.x25519Public,
                identityEd25519 = identity.ed25519Public,
                signedPreKeyId = spk.id,
                signedPreKey = spk.publicKey,
                signedPreKeySignature = spk.signature,
                oneTimePreKeyId = opks.firstOrNull()?.id,
                oneTimePreKey = opks.firstOrNull()?.publicKey,
            )

            // 2. Register the account + device with the backend.
            val response = authApi.createAccount(
                CreateAccountRequest(
                    araId = araId,
                    recoveryEmail = recoveryEmail?.ifBlank { null },
                    publicKeyBundle = bundle,
                    device = DeviceDto(deviceId = deviceId, name = android.os.Build.MODEL),
                )
            )

            // 3. Open a session (JWT + refresh).
            val session = authApi.createSession(SessionRequest(araId = response.araId, device = DeviceDto(deviceId = deviceId)))
            tokenStore.signIn(
                com.ara.core.common.AraAccount(
                    araId = response.araId,
                    displayName = response.araId,
                    recoveryEmail = recoveryEmail,
                    deviceId = deviceId,
                ),
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
            )

            // 4. Upload the full prekey batch and bootstrap local data.
            keyManager.oneTimePreKeysForUpload().let {
                authApi.uploadPreKeys(
                    com.ara.core.net.dto.PreKeyUploadRequest(oneTimePreKeys = it.map { k ->
                        com.ara.core.net.dto.OneTimePreKeyUploadDto(k.id, java.util.Base64.getEncoder().encodeToString(k.publicKey))
                    })
                )
            }
            chatDao.upsert(ChatEntity(id = ChatIds.savedMessages(response.araId), type = "SAVED", title = "Notes to self", peerId = response.araId))
            _createdAraId.value = response.araId
            signaling.connect()
        } catch (t: Throwable) {
            _error.value = t.message ?: "Account creation failed"
        } finally {
            _busy.value = false
        }
    }

    private fun randomBlock(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
        val bytes = ByteArray(4)
        java.security.SecureRandom().nextBytes(bytes)
        return buildString { repeat(4) { append(alphabet[(bytes[it].toInt() and 0xFF) % alphabet.length]) } }
    }

    fun consumeError() { _error.value = null }
}
