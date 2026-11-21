package com.noti.logger.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.shared.Diag
import com.noti.shared.FcmLabel
import com.noti.shared.FcmSender
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Main → companion: asks for the SMS since the newest one this phone holds. Run when FCM reports
 * that it deleted messages queued for this device (`onDeletedMessages`: over 100 pending during an
 * outage, or a device offline past their lifetime). The companion counted those relays as sent the
 * moment FCM accepted them, so without this they were gone for good unless someone pressed Repush.
 */
class CatchUpWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Settings.get(applicationContext)
        if (!s.peerPaired()) return@withContext Result.success()
        val newest = NotiDatabase.get(applicationContext).relayedMessageDao().newestIncomingAt()
        val since = sinceFor(newest)
        try {
            val payload = MessageCrypto.encrypt(Wire.encodeFor(WireMessage.CatchUp(since), com.noti.shared.Wire.Direction.MAIN_TO_COMPANION), s.relayKey)
            val res = FcmSender.forServiceAccount(s.serviceAccountJson)
                .send(s.sndiFcmToken, mapOf("payload" to payload), label = FcmLabel.CATCH_UP)
            when {
                res.ok -> { Diag.log("catch-up requested from the companion (since $since)"); Result.success() }
                res.httpCode == -1 || res.httpCode == 429 || res.httpCode in 500..599 -> Result.retry()
                else -> { Diag.log("catch-up request → HTTP ${res.httpCode} (${res.detail.take(40)})"); Result.success() }
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "catch-up-request"
        /** Overlap with what's already held: dedup drops the repeats, and the gap may begin a little
         *  before the newest message that did arrive (FCM doesn't deliver strictly in order). */
        const val OVERLAP_MS = 60 * 60_000L

        /** Where to start: just before the newest relay held, or 0 (the companion's default
         *  look-back) when there's nothing yet. Pure. */
        fun sinceFor(newestHeldMs: Long): Long =
            if (newestHeldMs <= 0) 0L else (newestHeldMs - OVERLAP_MS).coerceAtLeast(1L)

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CatchUpWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
