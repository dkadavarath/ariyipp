package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.noti.logger.config.Settings
import com.noti.logger.config.TriggerMode
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.NotificationEntity
import com.noti.logger.work.UploadWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Verifies the failure contract: a 4xx from the webhook must NOT delete records (only 2xx does)
 * and the worker must return retry so the batch is re-attempted.
 */
@RunWith(AndroidJUnit4::class)
class UploadFailureTest {

    private lateinit var ctx: Context
    private lateinit var server: ServerSocket
    private var port = 0

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        NotiDatabase.get(ctx).clearAllTables()
        startServerReturning(404)
        val s = Settings.get(ctx)
        s.bearerToken = "t"
        s.triggerMode = TriggerMode.MANUAL
        s.gzipEnabled = false
        s.webhookUrl = "http://127.0.0.1:$port/hook"
    }

    @After
    fun tearDown() {
        try { server.close() } catch (_: Exception) {}
        NotiDatabase.get(ctx).clearAllTables()
    }

    /** What the server answers; tests change it before running the worker. */
    @Volatile private var replyCode = 404
    @Volatile private var replyBody = ""

    private fun startServerReturning(code: Int) {
        replyCode = code
        server = ServerSocket(0)
        port = server.localPort
        thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    sock.use {
                        // drain request, then respond with the failure code
                        // Read the whole request (headers, then Content-Length bytes) before
                        // answering, or the client sees a reset instead of the response.
                        val input = it.getInputStream()
                        var length = 0
                        while (true) {
                            val line = StringBuilder()
                            while (true) {
                                val b = input.read()
                                if (b == -1 || b == '\n'.code) break
                                if (b != '\r'.code) line.append(b.toChar())
                            }
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                length = line.substring(15).trim().toIntOrNull() ?: 0
                            }
                        }
                        var left = length
                        val buf = ByteArray(4096)
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size, left))
                            if (n < 0) break
                            left -= n
                        }
                        val body = replyBody
                        val resp = "HTTP/1.1 $replyCode Status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        it.getOutputStream().write(resp.toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun entity(uid: String, postTime: Long) = NotificationEntity(
        uid = uid, packageName = "com.example.chat", appLabel = "Chat", postTime = postTime,
        title = "Hi", text = "there", bigText = null, subText = null, category = "msg",
        sbnKey = "k-$uid", uploaded = 0, createdAt = postTime
    )

    @Test
    fun http_4xx_retries_and_keeps_records() = runBlocking {
        val dao = NotiDatabase.get(ctx).notificationDao()
        val now = System.currentTimeMillis()
        dao.insert(entity("f1", now))
        dao.insert(entity("f2", now))
        assertEquals(2, dao.pendingCount())

        val result = TestListenableWorkerBuilder<UploadWorker>(ctx).build().doWork()

        assertTrue("4xx should retry", result is ListenableWorker.Result.Retry)
        assertEquals("records must NOT be deleted on 4xx", 2, dao.pendingCount())
        assertTrue(
            "status should reflect the http error",
            Settings.get(ctx).lastUploadResult?.contains("http 404") == true
        )
    }

    @Test
    fun n8n_default_reply_counts_the_batch_delivered_instead_of_retrying_forever() = runBlocking {
        replyCode = 200
        replyBody = """{"message":"Workflow was started"}"""
        Settings.get(ctx).unconfirmedNotifiedUrl = ""
        val dao = NotiDatabase.get(ctx).notificationDao()
        val now = System.currentTimeMillis()
        dao.insert(entity("n1", now))
        dao.insert(entity("n2", now))

        val result = TestListenableWorkerBuilder<UploadWorker>(ctx).build().doWork()

        assertTrue("a 2xx must not retry: $result / ${Settings.get(ctx).lastUploadResult}", result is ListenableWorker.Result.Success)
        assertEquals(0, dao.pendingCount())
        assertTrue(Settings.get(ctx).lastUploadResult?.contains("didn't list") == true)
        assertEquals("noted once for this endpoint", Settings.get(ctx).webhookUrl, Settings.get(ctx).unconfirmedNotifiedUrl)
    }

    @Test
    fun redirect_is_not_followed_and_keeps_records() = runBlocking {
        replyCode = 302
        val dao = NotiDatabase.get(ctx).notificationDao()
        dao.insert(entity("r1", System.currentTimeMillis()))

        val result = TestListenableWorkerBuilder<UploadWorker>(ctx).build().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(1, dao.pendingCount())
        assertTrue(Settings.get(ctx).lastUploadResult?.contains("http 302") == true)
    }
}
