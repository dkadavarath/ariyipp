package com.noti.logger.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noti.logger.config.Settings
import com.noti.shared.Diag
import com.noti.shared.Envelope
import com.noti.shared.FcmSender
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main → companion: sends this hub's push endpoint to the companion (durably - retries if offline),
 * the mirror of the companion's own token announce. Without it a rotated hub token (a data/cache
 * wipe, a Firebase reinstall) leaves the companion pushing relays, acks and heartbeats at a dead
 * endpoint while Main → companion keeps working - a one-way link only a re-pair used to fix.
 */
class HubTokenAnnounceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Settings.get(applicationContext)
        val token = s.fcmToken
        if (token.isBlank() || !s.peerPaired()) return@withContext Result.success()
        try {
            val payload = Envelope.seal(WireMessage.Token(token), Wire.Direction.MAIN_TO_COMPANION, s.relayKey)
            val res = FcmSender.forServiceAccount(s.serviceAccountJson).send(s.sndiFcmToken, mapOf("payload" to payload), label = com.noti.shared.FcmLabel.TOKEN_TO_COMPANION)
            when {
                res.ok -> {
                    // Record it so process-start announces (enqueueIfNeeded) stay quiet until the token changes.
                    s.announcedToken = token
                    Diag.log("hub endpoint announced to companion (id ${com.noti.shared.FcmLabel.messageId(res.detail)})")
                    Result.success()
                }
                res.httpCode == -1 || res.httpCode == 429 || res.httpCode in 500..599 -> Result.retry()
                else -> {
                    Diag.log("hub endpoint announce → HTTP ${res.httpCode} (${res.detail.take(40)})")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "hub-token-announce"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HubTokenAnnounceWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        /** Announce only when [token] differs from the last one the companion acknowledged. Used at
         *  every process start; forced paths (onNewToken, manual retry) call [enqueue]. */
        fun enqueueIfNeeded(context: Context, token: String) {
            val s = Settings.get(context)
            if (token.isBlank()) return
            if (token != s.fcmToken) s.fcmToken = token
            if (token != s.announcedToken) enqueue(context)
        }
    }
}
