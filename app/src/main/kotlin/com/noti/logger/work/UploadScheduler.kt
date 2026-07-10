package com.noti.logger.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.noti.logger.config.Settings
import com.noti.logger.config.TriggerMode
import java.util.concurrent.TimeUnit

object UploadScheduler {

    private const val ONE_SHOT_NAME = "upload-oneshot"
    private const val PERIODIC_NAME = "upload-periodic"
    private const val BACKOFF_DELAY_SECONDS = 30L

    /**
     * Derive the (NetworkType, requiresCharging) pair from settings.
     * Extracted as an internal function so pure-JVM unit tests can verify
     * the mapping without instantiating [Constraints] (which is Parcelable-based).
     */
    internal fun constraintDecisions(
        requireUnmetered: Boolean,
        requireCharging: Boolean
    ): Pair<NetworkType, Boolean> =
        Pair(
            if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
            requireCharging
        )

    fun buildConstraints(s: Settings): Constraints {
        val (networkType, charging) = constraintDecisions(s.requireUnmetered, s.requireCharging)
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(charging)
            .build()
    }

    fun enqueueOneShot(context: Context) {
        val s = Settings.get(context)
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(buildConstraints(s))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun schedulePeriodic(context: Context) {
        val s = Settings.get(context)
        if (s.triggerMode == TriggerMode.PERIODIC) {
            val request = PeriodicWorkRequestBuilder<UploadWorker>(
                s.intervalMinutes.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(buildConstraints(s))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            cancelPeriodic(context)
        }
    }

    /** Re-arms the periodic schedule (or cancels it) based on current [Settings]. */
    fun applyFromSettings(context: Context) {
        schedulePeriodic(context)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
    }
}
