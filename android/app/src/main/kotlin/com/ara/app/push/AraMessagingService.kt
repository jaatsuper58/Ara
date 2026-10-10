package com.ara.app.push

import com.ara.core.net.api.AuthApi
import com.ara.core.net.auth.TokenStore
import com.ara.core.net.dto.PushTokenRequest
import com.ara.core.net.ws.SignalingClient
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FCM push gateway receiver (spec §2 Notifications):
 * pushes carry a WAKE-UP ONLY — no sender, no content. The client reconnects
 * to signaling and pulls encrypted envelopes; nothing user-readable is shown
 * beyond "New message".
 */
@AndroidEntryPoint
class AraMessagingService : FirebaseMessagingService() {

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var authApi: AuthApi
    @Inject lateinit var signaling: SignalingClient

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            tokenStore.updatePushToken(token)
            runCatching {
                authApi.updatePushToken(PushTokenRequest(token = token, deviceId = tokenStore.deviceId()))
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Data-only wake-up: reconnect signaling to drain queued envelopes.
        signaling.connect()
    }
}
