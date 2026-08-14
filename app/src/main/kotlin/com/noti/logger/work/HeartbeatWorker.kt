package com.noti.logger.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.noti.logger.push.Heartbeat
import com.noti.shared.HeartbeatPolicy
import java.util.concurrent.TimeUnit

/**
 * Drives the liveness heartbeat: every run sends a beat to the peer and re-evaluates whether the peer
 * has gone silent (posting or clearing the warning). Scheduled periodically for both roles; also
 * enqueued one-shot to force a check (`request=true`) or to answer a peer's force-check (`false`).
 */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val request = inputData.getBoolean(KEY_REQUEST, false)
        Heartbeat.baselineIfNeeded(applicationContext)
        Heartbeat.send(applicationContext, request)
        Heartbeat.refreshNotification(applicationContext)
        return Result.success()
    }

    companion object {
        private const val KEY_REQUEST = "request"
        private const val PERIODIC_NAME = "heartbeat-periodic"
        private const val ONESHOT_NAME = "heartbeat-oneshot"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Keep-alive beat + staleness check every [HeartbeatPolicy.INTERVAL_MINUTES]. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                HeartbeatPolicy.INTERVAL_MINUTES, TimeUnit.MINUTES,
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        /** User pressed "Retry now": ping the peer and ask it to answer immediately. */
        fun retryNow(context: Context) = enqueueOneShot(context, request = true)

        /** Peer sent a force-check: answer with a plain beat so it sees us as alive. */
        fun answerNow(context: Context) = enqueueOneShot(context, request = false)

        private fun enqueueOneShot(context: Context, request: Boolean) {
            val work = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_REQUEST to request))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
