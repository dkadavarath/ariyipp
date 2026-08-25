package com.noti.sender

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noti.sender.config.SenderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends the companion's push endpoint to Main (durably - retries if offline). Enqueued after pairing
 * and whenever the FCM token rotates, so Main always has a live endpoint for reverse-send without the
 * user ever copying a token back by hand.
 */
class TokenAnnounceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = SenderSettings.get(applicationContext)
        when (SenderPipeline.announceToken(applicationContext)) {
            // Record it so process-start announces (enqueueIfNeeded) stay quiet until the token changes.
            SenderPipeline.SendOutcome.DELIVERED -> { s.announcedToken = s.myFcmToken; Result.success() }
            SenderPipeline.SendOutcome.TRANSIENT -> Result.retry()
            else -> Result.success() // permanent / not-configured: nothing more to do now
        }
    }

    companion object {
        private const val WORK_NAME = "token-announce"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TokenAnnounceWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        /** Announce only when the current token differs from the last one Main acknowledged. Used at
         *  every process start, where an unconditional announce would burn a full OAuth round-trip
         *  for no reason most of the time. Forced paths (pairing, onNewToken) call [enqueue]. */
        fun enqueueIfNeeded(context: Context) {
            val s = SenderSettings.get(context)
            if (s.myFcmToken.isNotBlank() && s.myFcmToken != s.announcedToken) enqueue(context)
        }
    }
}
