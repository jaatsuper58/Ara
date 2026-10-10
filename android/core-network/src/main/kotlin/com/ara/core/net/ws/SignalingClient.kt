package com.ara.core.net.ws

import com.ara.core.common.AppError
import com.ara.core.common.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.min

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Persistent WebSocket connection to the signaling gateway (one per device).
 *
 * The client relays opaque encrypted envelopes, call SDP/ICE, presence and
 * acks. Automatic reconnection uses exponential backoff; outbound messages
 * sent while offline are queued and flushed on reconnect.
 */
@Singleton
class SignalingClient @Inject constructor(
    @Named("ws") private val okHttpClient: OkHttpClient,
    @Named("wsUrl") private val wsUrl: String,
    private val tokenStore: com.ara.core.net.auth.TokenStore,
) {
    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

    private val _events = MutableSharedFlow<SignalingIn>(extraBufferCapacity = 256, replay = 0)
    val events: SharedFlow<SignalingIn> = _events

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val started = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    private val offlineQueue = ArrayDeque<String>()

    fun connect() {
        if (!started.compareAndSet(false, true)) return
        openSocket()
    }

    fun disconnect() {
        started.set(false)
        reconnectJob?.cancel()
        socket?.close(NORMAL_CLOSE, "client disconnect")
        socket = null
        _state.value = ConnectionState.DISCONNECTED
    }

    /** Sends a signaling message; queues it if the socket is down. */
    suspend fun send(message: SignalingOut): AppResult<Unit> {
        val text = SignalingJson.json.encodeToString(SignalingOut.serializer(), message)
        val ws = socket
        return if (ws != null && _state.value == ConnectionState.CONNECTED && ws.send(text)) {
            AppResult.Success(Unit)
        } else {
            synchronized(offlineQueue) {
                if (offlineQueue.size < MAX_OFFLINE_QUEUE) offlineQueue.addLast(text)
            }
            AppResult.Failure(AppError.network(IllegalStateException("Signaling socket offline — queued")))
        }
    }

    private fun openSocket() {
        _state.value = ConnectionState.CONNECTING
        scope.launch {
            val token = runCatching { tokenStore.accessToken() }.getOrNull()
            val url = wsUrl + (if (token != null) "?token=$token" else "")
            val request = Request.Builder().url(url).build()
            socket = okHttpClient.newWebSocket(request, listener)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            _state.value = ConnectionState.CONNECTED
            flushOfflineQueue()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching {
                SignalingJson.json.decodeFromString(SignalingIn.serializer(), text)
            }.getOrNull() ?: return
            _events.tryEmit(event)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    private fun flushOfflineQueue() {
        val pending = synchronized(offlineQueue) {
            val copy = offlineQueue.toList()
            offlineQueue.clear()
            copy
        }
        scope.launch {
            pending.forEach { text ->
                socket?.send(text)
            }
        }
    }

    private fun scheduleReconnect() {
        if (!started.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffSeconds = min(30.0, 1.0 * Math.pow(2.0, attempt.toDouble())).toLong()
            attempt = min(attempt + 1, 6)
            delay(backoffSeconds * 1000)
            openSocket()
        }
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        const val MAX_OFFLINE_QUEUE = 200
    }
}
