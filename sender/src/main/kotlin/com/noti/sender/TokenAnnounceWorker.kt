package com.noti.sender

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends the companion's push endpoint to Main (durably — retries if offline). Enqueued after pairing
 * and whenever the FCM token rotates, so Main always has a live endpoint for reverse-send without the
 * user ever copying a token back by hand.
 */
class TokenAnnounceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when (SenderPipeline.announceToken(applicationContext)) {
            SenderPipeline.SendOutcome.TRANSIENT -> Result.retry()
            else -> Result.success() // delivered / permanent / not-configured: nothing more to do now
        }
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "token-announce", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TokenAnnounceWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
