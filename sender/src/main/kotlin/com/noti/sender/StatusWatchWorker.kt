package com.noti.sender

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noti.sender.config.SenderSettings
import com.noti.sender.status.DeviceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The backstop under [DeviceMonitor]: re-evaluates the battery and wifi alert conditions on
 * WorkManager's cadence, so the alerts survive the thing that actually kills them in practice - an
 * OEM force-stopping the app mid-outage, after which no battery broadcast or network callback will
 * ever arrive again to notice the condition, or notice it cleared.
 *
 * 15 minutes is WorkManager's periodic floor, so an alert delayed to this path lands within that of
 * its threshold rather than at it. That is the right trade: the live callbacks are what make the
 * common case prompt, and a backstop that fired more often would burn battery on the companion for
 * a case that is meant to be rare.
 */
class StatusWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!CompanionRole.active(applicationContext)) {
            CompanionRole.stopAll(applicationContext)
            return@withContext Result.success()
        }
        DeviceMonitor.evaluate(applicationContext)
        Result.success()
    }

    companion object {
        private const val NAME = "status-watch-periodic"

        /**
         * The status backstop now rides on the SMS sync's periodic run (see SmsSyncWorker), so this
         * only makes sure that one is scheduled, and retires this worker's own 15-minute job left by
         * earlier versions - one wakeup instead of two.
         */
        fun schedule(context: Context) {
            cancel(context)
            if (SenderSettings.get(context).statusSharingEnabled) com.noti.sender.SmsSyncWorker.schedulePeriodic(context)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
