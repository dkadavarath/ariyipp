package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.noti.logger.config.Settings
import com.noti.logger.config.TriggerMode
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.NotificationEntity
import com.noti.logger.work.UploadWorker
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live integration test against the REAL webhook. Skipped unless the URL is supplied as an
 * instrumentation argument, so it never runs (or leaks secrets) in the normal suite.
 *
 * Run with:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.noti.logger.LiveWebhookTest \
 *     -Pandroid.testInstrumentationRunnerArguments.webhookUrl=<url> \
 *     -Pandroid.testInstrumentationRunnerArguments.authHeaderName=key \
 *     -Pandroid.testInstrumentationRunnerArguments.authKey=<token>
 */
@RunWith(AndroidJUnit4::class)
class LiveWebhookTest {

    @Test
    fun uploads_a_real_batch_to_the_configured_webhook() = kotlinx.coroutines.runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("webhookUrl").orEmpty()
        assumeTrue("No webhookUrl arg supplied; skipping live test.", url.isNotBlank())

        val headerName = args.getString("authHeaderName").orEmpty().ifBlank { "Authorization" }
        // Token is passed base64-encoded so shell-special chars (& $ * ! %) survive the
        // adb/instrumentation command line intact.
        val authKeyB64 = args.getString("authKeyB64").orEmpty()
        val authKey = if (authKeyB64.isBlank()) args.getString("authKey").orEmpty()
        else String(java.util.Base64.getDecoder().decode(authKeyB64), Charsets.UTF_8)
        // If a custom header name is used we send the raw token (no "Bearer " prefix).
        val prefix = if (headerName.equals("Authorization", ignoreCase = true)) "Bearer " else ""

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        NotiDatabase.get(ctx).clearAllTables()

        val settings = Settings.get(ctx)
        settings.webhookUrl = url
        settings.authHeaderName = headerName
        settings.authHeaderPrefix = prefix
        settings.bearerToken = authKey
        settings.triggerMode = TriggerMode.MANUAL
        settings.retentionDays = 30

        val now = System.currentTimeMillis()
        val dao = NotiDatabase.get(ctx).notificationDao()
        dao.insert(
            NotificationEntity(
                uid = "live-test-$now",
                packageName = "com.noti.logger.livetest",
                appLabel = "noti live test",
                postTime = now,
                title = "noti live test",
                text = "End-to-end upload from the instrumented test at $now",
                bigText = null,
                subText = null,
                category = "msg",
                sbnKey = "live-k-$now",
                uploaded = 0,
                createdAt = now
            )
        )

        val worker = TestListenableWorkerBuilder<UploadWorker>(ctx).build()
        val result = worker.doWork()

        assertEquals(
            "Webhook did not return 2xx. lastUploadResult=${settings.lastUploadResult}",
            ListenableWorker.Result.success(),
            result
        )
        assertEquals("row should be marked uploaded", 0, dao.pendingCount())

        NotiDatabase.get(ctx).clearAllTables()
    }
}
