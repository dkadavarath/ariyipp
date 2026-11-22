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
import com.noti.shared.Diag
import com.noti.shared.HeartbeatPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives the liveness heartbeat. The periodic run sends a routine beat; if the peer has gone quiet
 * it sends a high-priority force-check instead and schedules a [MODE_VERIFY] run, and only that run
 * - finding no reply since the force-check - posts the warning. Staleness alone never alarms: under
 * Doze a healthy peer routinely misses the window. Also enqueued one-shot for "Retry now" (a
 * force-check) and as the fallback answer to a peer's force-check.
 */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Heartbeat.send() is blocking HttpURLConnection code - keep it off Dispatchers.Default
        // like every other network-touching worker here.
        val ctx = applicationContext
        when (inputData.getInt(KEY_MODE, MODE_ROUTINE)) {
            MODE_ANSWER -> Heartbeat.send(ctx, request = false, highPriority = true)
            MODE_PROBE -> probe(ctx)
            MODE_VERIFY -> {
                val probeAt = inputData.getLong(KEY_PROBE_AT, 0L)
                if (!HeartbeatPolicy.answeredSince(Heartbeat.lastBeatAtMs(ctx), probeAt)) {
                    Diag.log("heartbeat: no answer to the force-check - ${Heartbeat.peerLabel(ctx)} looks offline")
                }
                // Answered → no longer stale → clears; unanswered → still stale → warns.
                Heartbeat.refreshNotification(ctx)
            }
            else -> {
                Heartbeat.baselineIfNeeded(ctx)
                if (Heartbeat.isDisconnected(ctx)) probe(ctx)
                else {
                    Heartbeat.send(ctx, request = false)
                    Heartbeat.refreshNotification(ctx)
                }
            }
        }
        Result.success()
    }

    /** Ask the peer to answer now, and come back after [HeartbeatPolicy.PROBE_WAIT_MINUTES] to see
     *  whether it did. If the force-check can't even be sent, fall back to judging staleness now. */
    private fun probe(ctx: Context) {
        val at = System.currentTimeMillis()
        if (Heartbeat.send(ctx, request = true)) scheduleVerify(ctx, at)
        else Heartbeat.refreshNotification(ctx)
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_PROBE_AT = "probe_at"
        private const val MODE_ROUTINE = 0
        private const val MODE_PROBE = 1
        private const val MODE_ANSWER = 2
        private const val MODE_VERIFY = 3

        private const val PERIODIC_NAME = "heartbeat-periodic"
        private const val ONESHOT_NAME = "heartbeat-oneshot"
        private const val ANSWER_NAME = "heartbeat-answer"
        private const val VERIFY_NAME = "heartbeat-verify"

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
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(PERIODIC_NAME)
            wm.cancelUniqueWork(VERIFY_NAME)
        }

        /** "Retry now" (or the Status screen finding the peer stale): force-check, then verify. */
        fun retryNow(context: Context) = enqueue(context, ONESHOT_NAME, MODE_PROBE)

        /** Fallback when the inline answer to a peer's force-check couldn't be sent. Its own unique
         *  name so it can't replace (or be replaced by) a pending force-check of ours. */
        fun answerNow(context: Context) = enqueue(context, ANSWER_NAME, MODE_ANSWER)

        private fun enqueue(context: Context, name: String, mode: Int) {
            val work = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_MODE to mode))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, work)
        }

        /** Local-only check, so no network constraint: it just reads the last-seen time. */
        private fun scheduleVerify(context: Context, probeAtMs: Long) {
            val work = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setInitialDelay(HeartbeatPolicy.PROBE_WAIT_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MODE to MODE_VERIFY, KEY_PROBE_AT to probeAtMs))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(VERIFY_NAME, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
