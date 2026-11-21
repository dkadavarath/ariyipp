package com.noti.sender

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends a delivery ack to Main off the FCM callback. The ack is a full FCM send (and an OAuth
 * refresh when the token has expired), which can outlast the ~20 s Android gives a push handler;
 * done inline before the SMS, a slow ack could get the process killed with the command already
 * counted as seen and the SMS never sent. Main applies acks monotonically, so this one arriving
 * after a later "sent" ack is harmless.
 */
class AckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val msgId = inputData.getLong(KEY_MSG, 0L)
        val status = inputData.getInt(KEY_STATUS, 0)
        val reason = inputData.getString(KEY_REASON).orEmpty()
        if (msgId <= 0) return@withContext Result.success()
        when (SenderPipeline.pushAckToIppu(applicationContext, msgId, status, reason)) {
            SenderPipeline.SendOutcome.TRANSIENT -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        private const val KEY_MSG = "msg"
        private const val KEY_STATUS = "status"
        private const val KEY_REASON = "reason"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context, msgId: Long, status: Int, reason: String = "") {
            if (msgId <= 0) return
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<AckWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(KEY_MSG to msgId, KEY_STATUS to status, KEY_REASON to reason))
                    .build()
            )
        }
    }
}
