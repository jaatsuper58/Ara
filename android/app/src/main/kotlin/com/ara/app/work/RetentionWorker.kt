package com.ara.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ara.core.common.Constants
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.dao.MessageDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Local auto-purge (spec §2 Files & media + Disappearing messages):
 *  - deletes messages whose disappearing timer has elapsed,
 *  - enforces per-chat media retention (forever / 30d / 7d / 3d).
 * Runs daily; content never leaves the device.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()

            // 1. Disappearing messages.
            messageDao.purgeExpired(now)

            // 2. Per-chat media retention.
            val chats = chatDao.observeAll().first()
            chats.forEach { chat ->
                val days = chat.mediaRetentionDays
                if (days > 0) {
                    val cutoff = now - days * 86_400_000L
                    messageDao.purgeOlderThan(listOf(chat.id), cutoff)
                }
            }
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "ara_retention"

        /** Default retention policy applied to new chats. */
        val DEFAULT_RETENTION_DAYS: Int = Constants.MEDIA_RETENTION_OPTIONS_DAYS.first()
    }
}
