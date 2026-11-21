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
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsInbox
import com.noti.shared.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The single relay path: scans the SMS provider for rows past a per-leg high-water `_id` and relays
 * them. Triggered by an incoming-SMS broadcast, by boot, by the manual button, and periodically as a
 * backstop - all the same scan. Keying off the provider's stable, monotonic `_id` means a duplicate
 * SMS_RECEIVED broadcast (a Samsung / dual-SIM quirk) can't double-relay: the provider has one row,
 * and the mark only ever moves forward. Each leg (ippu / webhook) has its own mark, so one leg
 * failing and retrying never re-sends the other.
 */
class SmsSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!CompanionRole.active(ctx)) {
            // A job left over from when this phone was the companion: never relay from Main.
            CompanionRole.stopAll(ctx)
            return@withContext Result.success()
        }
        // The periodic run also re-checks device status (low battery, wifi lost) - the backstop the
        // separate StatusWatchWorker used to be. One wakeup every 15 minutes instead of two.
        if (inputData.getBoolean(KEY_PERIODIC, false) && SenderSettings.get(ctx).statusSharingEnabled) {
            com.noti.sender.status.DeviceMonitor.evaluate(ctx)
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.success() // can't read the provider
        }
        // One scan at a time in-process, so a broadcast scan and the periodic scan can't both read the
        // same rows before either mark advances.
        val result = scanMutex.withLock { scanAndRelay(ctx) }
        // A broadcast-triggered scan that found nothing usually means the default SMS app hadn't
        // written the row yet (cold start, slow OEM). Look again shortly rather than leaving the
        // message for the 15-minute backstop.
        val followUp = inputData.getInt(KEY_FOLLOW_UP, -1)
        if (followUp in 0 until FOLLOW_UP_DELAYS_S.size && !foundRows) {
            enqueueFollowUp(ctx, followUp)
        }
        result
    }

    /** Whether this run's scan saw any row past a mark (set by [scanAndRelay]). */
    private var foundRows = false

    private fun scanAndRelay(ctx: Context): Result {
        val s = SenderSettings.get(ctx)
        var retry = false

        // ---- ippu (FCM) leg ----
        if (SenderPipeline.isConfigured(s)) {
            if (s.lastRelayedSmsId == -1L) {
                s.lastRelayedSmsId = SmsInbox.newestId(ctx)
                s.lastRelayedSmsDate = SmsInbox.dateOf(ctx, s.lastRelayedSmsId)
                Diag.log("relay: baseline at id ${s.lastRelayedSmsId} - new SMS will relay to main")
            } else {
                if (s.lastRelayedSmsDate == 0L) s.lastRelayedSmsDate = SmsInbox.dateOf(ctx, s.lastRelayedSmsId)
                val rows = SmsInbox.pending(ctx, s.lastRelayedSmsId, s.lastRelayedSmsDate)
                if (rows.isNotEmpty()) foundRows = true
                if (rows.isNotEmpty()) Diag.log("relay: ${rows.size} new SMS → main")
                for (sms in rows) {
                    when (SenderPipeline.pushToIppu(ctx, sms)) {
                        SenderPipeline.SendOutcome.DELIVERED,
                        SenderPipeline.SendOutcome.PERMANENT -> {
                            s.lastRelayedSmsId = sms.id; s.lastRelayedSmsDate = sms.receivedMillis
                        }
                        // BLOCKED holds the mark too: skipping would lose the SMS for good.
                        SenderPipeline.SendOutcome.TRANSIENT,
                        SenderPipeline.SendOutcome.BLOCKED -> { retry = true; break }
                        SenderPipeline.SendOutcome.NOT_CONFIGURED -> break
                    }
                }
            }
        }

        // ---- webhook (n8n) leg - independent high-water mark ----
        if (s.n8nEnabled && s.n8nUrl.isNotBlank()) {
            if (s.lastWebhookSmsId == -1L) {
                s.lastWebhookSmsId = SmsInbox.newestId(ctx)
                s.lastWebhookSmsDate = SmsInbox.dateOf(ctx, s.lastWebhookSmsId)
                Diag.log("webhook: baseline at id ${s.lastWebhookSmsId}")
            } else {
                if (s.lastWebhookSmsDate == 0L) s.lastWebhookSmsDate = SmsInbox.dateOf(ctx, s.lastWebhookSmsId)
                val rows = SmsInbox.pending(ctx, s.lastWebhookSmsId, s.lastWebhookSmsDate)
                if (rows.isNotEmpty()) foundRows = true
                if (rows.isNotEmpty()) Diag.log("webhook: ${rows.size} new SMS → n8n")
                for (sms in rows) {
                    when (SenderPipeline.pushToWebhook(ctx, sms)) {
                        SenderPipeline.SendOutcome.DELIVERED,
                        SenderPipeline.SendOutcome.PERMANENT -> {
                            s.lastWebhookSmsId = sms.id; s.lastWebhookSmsDate = sms.receivedMillis
                        }
                        SenderPipeline.SendOutcome.TRANSIENT,
                        SenderPipeline.SendOutcome.BLOCKED -> { retry = true; break }
                        SenderPipeline.SendOutcome.NOT_CONFIGURED -> break
                    }
                }
            }
        }

        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE = "sms-scan"
        // Its own name: enqueueing under UNIQUE with REPLACE from inside a running scan would
        // cancel that scan.
        private const val FOLLOW_UP = "sms-scan-follow-up"
        private const val KEY_FOLLOW_UP = "follow_up"
        private const val KEY_PERIODIC = "periodic"
        /** Re-check delays after a broadcast scan that found no new row yet. */
        private val FOLLOW_UP_DELAYS_S = longArrayOf(5, 15)
        private const val PERIODIC = "sms-scan-periodic"

        // Serializes scans across the one-shot and periodic workers (same process).
        private val scanMutex = Mutex()

        /**
         * Sets each active leg's high-water mark to the newest row now, so we don't backfill the
         * pre-existing inbox - and, crucially, so the mark is in place *before* the first new SMS,
         * so that message isn't skipped. Safe to call often; no-ops once a mark is set. Runs on the
         * caller's thread (a single-row provider query); needs READ_SMS.
         */
        fun baselineIfNeeded(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
            val s = SenderSettings.get(context)
            val fcmNeeds = SenderPipeline.isConfigured(s) && s.lastRelayedSmsId == -1L
            val n8nNeeds = s.n8nEnabled && s.n8nUrl.isNotBlank() && s.lastWebhookSmsId == -1L
            if (!fcmNeeds && !n8nNeeds) return
            val newest = SmsInbox.newestId(context)
            val date = SmsInbox.dateOf(context, newest)
            if (fcmNeeds) { s.lastRelayedSmsId = newest; s.lastRelayedSmsDate = date }
            if (n8nNeeds) { s.lastWebhookSmsId = newest; s.lastWebhookSmsDate = date }
        }

        /** Scan and relay new rows immediately (manual button, boot). */
        fun scanNow(context: Context) = enqueue(context, 0L)

        /** Scan shortly after an SMS broadcast; the delay lets the default SMS app write the row.
         *  If the row still isn't there, the scan re-checks after [FOLLOW_UP_DELAYS_S]. */
        fun scanSoon(context: Context) = enqueue(context, 2L, followUp = 0)

        private fun enqueue(context: Context, delaySeconds: Long, followUp: Int = -1) {
            // REPLACE so a burst (or a duplicate broadcast) collapses to one scan of the latest state.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request(delaySeconds, followUp))
        }

        private fun enqueueFollowUp(context: Context, attempt: Int) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                FOLLOW_UP, ExistingWorkPolicy.REPLACE,
                request(FOLLOW_UP_DELAYS_S[attempt], attempt + 1),
            )
        }

        private fun request(delaySeconds: Long, followUp: Int) =
            OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .apply { if (delaySeconds > 0) setInitialDelay(delaySeconds, TimeUnit.SECONDS) }
                .setInputData(androidx.work.workDataOf(KEY_FOLLOW_UP to followUp))
                .build()

        fun schedulePeriodic(context: Context) {
            // UPDATE, not KEEP: an install from before the status check moved here holds a request
            // without the periodic flag; UPDATE swaps it in place and keeps the schedule.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SmsSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(androidx.work.workDataOf(KEY_PERIODIC to true))
                    .build(),
            )
        }
    }
}
