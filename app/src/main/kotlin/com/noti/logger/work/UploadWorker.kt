package com.noti.logger.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noti.logger.alert.Alerter
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.NotificationDao
import com.noti.shared.UploadBatch
import com.noti.logger.upload.UploadOutcome
import com.noti.logger.upload.Uploader
import com.noti.logger.upload.alreadyExistsUids
import com.noti.logger.upload.genuineFailures
import com.noti.logger.upload.toUploadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val settings = Settings.get(applicationContext)
            if (settings.webhookUrl.isBlank()) return@withContext Result.success()

            val dao = NotiDatabase.get(applicationContext).notificationDao()
            val uploader = Uploader()
            val maxPages = 50
            var pagesScanned = 0
            var totalFailed = 0
            val failureMessages = ArrayList<String>()

            // Validation lives in n8n; the endpoint always replies 200 with per-uid
            // success/failure lists. Only uids echoed in `success` are deleted; every other
            // uid in the batch is retried, and failures are surfaced to the user.
            while (pagesScanned < maxPages) {
                val page = dao.pendingBatch(100)
                if (page.isEmpty()) break
                pagesScanned++
                val drained = page.size < 100

                val uploadBatch = UploadBatch(
                    batch = page.map { it.toUploadItem(settings.deviceId) }
                )

                when (val outcome = uploader.upload(
                    settings.webhookUrl,
                    settings.authHeaderName,
                    settings.authHeaderValue(),
                    uploadBatch,
                    gzip = settings.gzipEnabled
                )) {
                    is UploadOutcome.Parsed -> {
                        // Delete uids confirmed stored, plus "already exists" duplicates
                        // (already stored downstream → idempotent success, don't retry forever).
                        val deletableUids = outcome.successUids + outcome.failures.alreadyExistsUids()
                        val succeededIds = page.filter { it.uid in deletableUids }.map { it.id }
                        if (succeededIds.isNotEmpty()) dao.markUploaded(succeededIds)

                        val genuineFailures = outcome.failures.genuineFailures()
                        val failedInPage = page.size - succeededIds.size
                        if (failedInPage > 0) {
                            totalFailed += failedInPage
                            failureMessages.addAll(genuineFailures)
                            // Unconfirmed records stay pending; stop and let WorkManager retry them.
                            break
                        }
                        if (drained) break // queue fully drained, all confirmed
                    }
                    UploadOutcome.Retry -> {
                        settings.lastUploadResult = "retry"
                        return@withContext Result.retry()
                    }
                    is UploadOutcome.ClientError -> {
                        // Transport-level rejection (auth/URL). Nothing deleted. Alert + retry.
                        Alerter.alertUploadRejected(applicationContext, outcome.code, page.size)
                        settings.lastUploadResult = "http ${outcome.code} — alerted, retrying"
                        settings.lastUploadAtMs = System.currentTimeMillis()
                        return@withContext Result.retry()
                    }
                }
            }

            val now = System.currentTimeMillis()
            settings.lastUploadAtMs = now

            purgeOldRecords(dao, settings, now)

            if (totalFailed > 0) {
                // Some records were rejected by the endpoint. Inform the user and retry them.
                Alerter.alertUploadFailures(applicationContext, totalFailed, failureMessages)
                settings.lastUploadResult = "$totalFailed failed — retrying"
                return@withContext Result.retry()
            }

            settings.lastUploadResult = "ok"
            Result.success()
        } catch (e: Exception) {
            try {
                Settings.get(applicationContext).lastUploadResult = "error: ${e.message}"
            } catch (_: Exception) {
                // best-effort
            }
            Result.retry()
        }
    }

    private suspend fun purgeOldRecords(dao: NotificationDao, settings: Settings, now: Long) {
        val retentionDays = settings.retentionDays
        // retentionDays == 0  → cutoff = now  → purge all uploaded records immediately
        // retentionDays  > 0  → cutoff = now minus retention window
        val cutoff = if (retentionDays <= 0) now
                     else now - retentionDays.toLong() * 86_400_000L
        dao.purgeUploadedOlderThan(cutoff)
    }
}
