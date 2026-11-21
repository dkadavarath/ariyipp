package com.noti.logger.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.noti.logger.config.Settings
import com.noti.logger.reminder.ReminderScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs the rulebook on its schedule, on demand from the "Calculate now" button, and whenever a
 * message a rule could be about arrives ([runForEvent]). The schedule stays as the safety net for
 * anything an event run missed (the process killed mid-scan, a rule written after the message).
 *
 * The periodic run is what makes the module passive - you are not meant to open the app for a bill
 * to be noticed. The manual run exists because a scan is idempotent (see [ReminderScanner]), so
 * there is no reason to make anyone wait until tomorrow to see whether a rule they just wrote works.
 */
class ReminderScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val full = inputData.getBoolean(KEY_FULL, false)
        ReminderScanner.scan(applicationContext, fullRescan = full)
        ReminderScanner.purgeHistory(applicationContext)
        Result.success()
    }

    companion object {
        private const val KEY_FULL = "full"
        private const val PERIODIC_NAME = "reminder-scan-periodic"
        private const val ONESHOT_NAME = "reminder-scan-oneshot"
        private const val EVENT_NAME = "reminder-scan-event"

        /** Apply the module's current schedule: on and periodic, or cancelled. */
        fun applyFromSettings(context: Context) {
            val s = Settings.get(context)
            if (!s.reminderEnabled || s.reminderScanHours <= 0) {
                cancelPeriodic(context)
                return
            }
            // WorkManager's floor is 15 minutes; the setting is in hours, so it can't go under.
            val request = PeriodicWorkRequestBuilder<ReminderScanWorker>(
                s.reminderScanHours.toLong(), TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }

        /** "Calculate now". [full] re-reads the whole lookback window rather than resuming from the
         *  last scan - what you want after writing or fixing a rule. */
        fun runNow(context: Context, full: Boolean = true) {
            val work = OneTimeWorkRequestBuilder<ReminderScanWorker>()
                .setInputData(workDataOf(KEY_FULL to full))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.REPLACE, work)
        }

        /**
         * A message a rule could be about just arrived (see [com.noti.logger.reminder.ReminderTrigger]):
         * scan what's new now. Queued behind a scan already running rather than replacing it, since
         * that one may have read its window before this message was written. A burst - a bank's three
         * SMS in a row - collapses: while a scan is still waiting to start it will see them all, so
         * no second one is queued behind it. Blocking - call off the main thread.
         */
        fun runForEvent(context: Context) {
            val wm = WorkManager.getInstance(context)
            val waiting = try {
                wm.getWorkInfosForUniqueWork(EVENT_NAME).get()
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            } catch (e: Exception) {
                false
            }
            if (waiting) return
            val work = OneTimeWorkRequestBuilder<ReminderScanWorker>()
                .setInputData(workDataOf(KEY_FULL to false))
                .build()
            wm.enqueueUniqueWork(EVENT_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}
