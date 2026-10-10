package com.ara.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ara.core.net.ws.ConnectionState
import com.ara.core.net.ws.SignalingClient
import com.ara.feature.chat.data.ChatRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background sync/retry (spec §3): keeps the signaling socket alive and
 * flushes messages that stayed PENDING while offline.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val signaling: SignalingClient,
    private val chatRepository: ChatRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (signaling.state.value != ConnectionState.CONNECTED) {
                signaling.connect()
            }
            chatRepository.flushPending()
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "ara_sync"
    }
}
