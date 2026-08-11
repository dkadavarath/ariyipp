package com.noti.sender

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.util.Log
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Backstop for messages missed while the app was asleep/killed: reads the SMS inbox past a
 * high-water mark and relays any that weren't sent. noti dedups against the live relay by content
 * key, so re-sending an already-delivered SMS is harmless.
 */
class SmsSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val s = SenderSettings.get(ctx)

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.success() // can't read the inbox
        }
        if (!SenderPipeline.isConfigured(s)) return@withContext Result.success() // not paired; don't advance

        // First run: baseline to the newest message so we don't backfill the whole history.
        if (s.lastSyncedSmsDate == 0L) {
            s.lastSyncedSmsDate = SmsInbox.newestDate(ctx).coerceAtLeast(1L)
            Log.i(TAG, "sync baseline set to ${s.lastSyncedSmsDate}")
            com.noti.shared.Diag.log("sync: baseline set (future missed SMS will be caught)")
            return@withContext Result.success()
        }

        val missed = SmsInbox.since(ctx, s.lastSyncedSmsDate)
        if (missed.isEmpty()) return@withContext Result.success()
        Log.i(TAG, "sync: ${missed.size} message(s) since ${s.lastSyncedSmsDate}")
        com.noti.shared.Diag.log("sync: ${missed.size} missed SMS to relay")
        for (sms in missed) {
            val ok = SenderPipeline.relay(ctx, sms)
            if (!ok) return@withContext Result.retry() // transient; retry, don't advance past this one
            s.lastSyncedSmsDate = maxOf(s.lastSyncedSmsDate, sms.receivedMillis)
        }
        Result.success()
    }

    companion object {
        private const val TAG = "noti-sender"
        private const val PERIODIC = "sms-sync-periodic"
        private const val ONESHOT = "sms-sync-now"

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmsSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SmsSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
