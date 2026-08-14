package com.noti.sender

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsInbox
import com.noti.shared.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One-shot "push my whole SMS inbox to Main" so Main can rebuild its history after a gap. Walks the
 * inbox oldest-first (FCM leg only — no n8n) and relies on Main's content-hash dedup to drop anything
 * it already holds. Resumable: a cursor is advanced per message, so a transient network failure just
 * retries and picks up where it left off. Re-pressing the button starts a fresh full pass (cursor 0).
 */
class RepushWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val s = SenderSettings.get(ctx)

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Diag.log("repush: can't run — SMS read access not granted")
            RepushNotice.done(ctx, ctx.getString(R.string.repush_note_no_read))
            return@withContext Result.success()
        }
        if (!SenderPipeline.isConfigured(s)) {
            Diag.log("repush: can't run — not paired with Main")
            RepushNotice.done(ctx, ctx.getString(R.string.repush_note_not_paired))
            return@withContext Result.success()
        }

        val cursor = s.repushCursorId
        val batch = SmsInbox.since(ctx, cursor)
        if (cursor == 0L) s.repushTotal = batch.size
        val total = s.repushTotal.coerceAtLeast(1)
        val base = (total - batch.size).coerceAtLeast(0) // already handled on prior (retried) runs

        Diag.log("repush: pushing ${batch.size} SMS to Main (of $total)")
        RepushNotice.progress(ctx, base, total, paused = false)

        var delivered = 0
        var failed = 0
        var consecutivePermanent = 0
        // Track the resume point in memory and only persist it every 25 messages (plus on
        // pause/finish), instead of an encrypted-prefs write per message across a big inbox. On an
        // unclean kill we re-push at most the last 25 — harmless, ippu dedups them.
        var localCursor = cursor
        for (sms in batch) {
            when (SenderPipeline.pushToIppu(ctx, sms)) {
                SenderPipeline.SendOutcome.DELIVERED -> { delivered++; consecutivePermanent = 0 }
                SenderPipeline.SendOutcome.PERMANENT -> {
                    failed++; consecutivePermanent++
                    if (delivered == 0 && consecutivePermanent >= 5) {
                        s.repushCursorId = localCursor
                        Diag.log("repush: aborted — Main/FCM rejected every message (check the shared key & Main token)")
                        RepushNotice.done(ctx, ctx.getString(R.string.repush_note_rejected))
                        return@withContext Result.success()
                    }
                }
                SenderPipeline.SendOutcome.TRANSIENT -> {
                    s.repushCursorId = localCursor
                    val at = base + delivered + failed
                    Diag.log("repush: paused at $at/$total (no network) — resumes automatically")
                    RepushNotice.progress(ctx, at, total, paused = true)
                    return@withContext Result.retry()
                }
                SenderPipeline.SendOutcome.NOT_CONFIGURED -> return@withContext Result.success()
            }
            localCursor = maxOf(localCursor, sms.id)
            val attempted = base + delivered + failed
            if (attempted % 25 == 0) {
                s.repushCursorId = localCursor
                RepushNotice.progress(ctx, attempted, total, paused = false)
            }
            delay(40) // gentle pacing so a big inbox doesn't hammer FCM
        }

        s.repushCursorId = 0L // full pass complete — next press starts fresh
        Diag.log("repush: complete — $delivered pushed" + if (failed > 0) ", $failed rejected" else "")
        RepushNotice.done(
            ctx,
            ctx.getString(R.string.repush_note_done, base + delivered) +
                if (failed > 0) ctx.getString(R.string.repush_note_failed_suffix, failed) else "",
        )
        Result.success()
    }

    companion object {
        private const val UNIQUE = "sms-repush"

        /** Kick off a fresh full repush of the entire inbox (resets the resume cursor). */
        fun start(context: Context) {
            SenderSettings.get(context).repushCursorId = 0L
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RepushWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

/** Ongoing progress + final-result notification for the repush (a plain notification, not an FGS). */
private object RepushNotice {

    private const val CHANNEL = "ariy_repush"
    private const val ID = 4711

    fun progress(context: Context, done: Int, total: Int, paused: Boolean) {
        ensureChannel(context)
        val text = if (paused)
            context.getString(R.string.repush_note_paused, done, total)
        else
            context.getString(R.string.repush_note_progress, done, total)
        val n = baseBuilder(context)
            .setContentText(text)
            .setProgress(total, done, false)
            .setOngoing(!paused)
            .build()
        notify(context, n)
    }

    fun done(context: Context, text: String) {
        ensureChannel(context)
        val n = baseBuilder(context)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        notify(context, n)
    }

    private fun baseBuilder(context: Context) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_status_active)
            .setContentTitle(context.getString(R.string.repush_note_title))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun notify(context: Context, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.repush_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
