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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        val result = scanMutex.withLock {
            running = when {
                UNIQUE_NEXT in tags -> UNIQUE_NEXT
                UNIQUE in tags -> UNIQUE
                else -> OTHER // periodic or follow-up: never replaced by [enqueue]
            }
            try { scanAndRelay(ctx) } finally { running = null }
        }
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
        // Each leg has its own mark, so one leg failing and retrying never re-sends the other.
        val fcm = if (SenderPipeline.isConfigured(s)) relayLeg(ctx, MainLeg(ctx, s)) else LegResult.DONE
        val n8n = if (s.n8nEnabled && s.n8nUrl.isNotBlank()) relayLeg(ctx, WebhookLeg(ctx, s)) else LegResult.DONE
        return if (fcm == LegResult.RETRY || n8n == LegResult.RETRY) Result.retry() else Result.success()
    }

    private enum class LegResult { DONE, RETRY }

    /** Where one leg sends, and its own mark (the last SMS it sent: provider id and time). */
    private abstract class Leg {
        abstract val label: String
        abstract val destination: String
        abstract val baselineNote: String?
        abstract var markId: Long
        abstract var markDate: Long
        abstract fun send(sms: com.noti.sender.sms.CapturedSms): SenderPipeline.SendOutcome
    }

    private class MainLeg(private val ctx: Context, private val s: SenderSettings) : Leg() {
        override val label = "relay"
        override val destination = "main"
        override val baselineNote = "new SMS will relay to main"
        override var markId by s::lastRelayedSmsId
        override var markDate by s::lastRelayedSmsDate
        override fun send(sms: com.noti.sender.sms.CapturedSms) = SenderPipeline.pushToIppu(ctx, sms)
    }

    private class WebhookLeg(private val ctx: Context, private val s: SenderSettings) : Leg() {
        override val label = "webhook"
        override val destination = "n8n"
        override val baselineNote: String? = null
        override var markId by s::lastWebhookSmsId
        override var markDate by s::lastWebhookSmsDate
        override fun send(sms: com.noti.sender.sms.CapturedSms) = SenderPipeline.pushToWebhook(ctx, sms)
    }

    /**
     * One leg's pass over the inbox: the first time, set the mark at the newest SMS (no backfill);
     * after that, send everything past it, in order, moving the mark after each one sent (or
     * skipped as unsendable). A transient failure, or a setup the destination refuses (BLOCKED),
     * stops the pass with the mark held, so nothing is skipped.
     */
    private fun relayLeg(ctx: Context, leg: Leg): LegResult {
        if (leg.markId == -1L) {
            leg.markId = SmsInbox.newestId(ctx)
            leg.markDate = SmsInbox.dateOf(ctx, leg.markId)
            Diag.log("${leg.label}: baseline at id ${leg.markId}" + (leg.baselineNote?.let { " - $it" } ?: ""))
            return LegResult.DONE
        }
        if (leg.markDate == 0L) leg.markDate = SmsInbox.dateOf(ctx, leg.markId)
        val rows = SmsInbox.pending(ctx, leg.markId, leg.markDate)
        if (rows.isEmpty()) return LegResult.DONE
        foundRows = true
        Diag.log("${leg.label}: ${rows.size} new SMS → ${leg.destination}")
        for (sms in rows) {
            when (leg.send(sms)) {
                SenderPipeline.SendOutcome.DELIVERED,
                SenderPipeline.SendOutcome.PERMANENT -> {
                    val (id, date) = SmsInbox.advance(leg.markId, leg.markDate, sms)
                    leg.markId = id; leg.markDate = date
                }
                // BLOCKED holds the mark too: skipping would lose the SMS for good.
                SenderPipeline.SendOutcome.TRANSIENT,
                SenderPipeline.SendOutcome.BLOCKED -> return LegResult.RETRY
                SenderPipeline.SendOutcome.NOT_CONFIGURED -> return LegResult.DONE
            }
        }
        return LegResult.DONE
    }

    companion object {
        private const val UNIQUE = "sms-scan"
        private const val UNIQUE_NEXT = "sms-scan-next"

        private const val OTHER = "other"

        /** The name of the scan sending right now in this process, if any - see [enqueue]. */
        @Volatile private var running: String? = null
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
            // REPLACE so a burst (or a duplicate broadcast) collapses to one scan of the latest state,
            // and a scan waiting out a retry backoff is retried now. But never a scan in the middle
            // of sending: cancelling it there sent an SMS FCM had taken, but the mark hadn't yet
            // recorded, a second time. While one runs, the next goes under the other name and waits
            // for it on [scanMutex].
            val name = if (running == UNIQUE) UNIQUE_NEXT else UNIQUE
            WorkManager.getInstance(context)
                .enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request(name, delaySeconds, followUp))
        }

        private fun enqueueFollowUp(context: Context, attempt: Int) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                FOLLOW_UP, ExistingWorkPolicy.REPLACE,
                request(FOLLOW_UP, FOLLOW_UP_DELAYS_S[attempt], attempt + 1),
            )
        }

        /** Tagged with its unique [name], so a running scan knows which one it is. */
        private fun request(name: String, delaySeconds: Long, followUp: Int) =
            OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .addTag(name)
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
