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
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Core contract: the endpoint returns per-uid success/failure lists. Only uids echoed in
 * `success` are deleted; the rest stay pending and the worker returns retry.
 */
@RunWith(AndroidJUnit4::class)
class PartialSuccessTest {

    private lateinit var ctx: Context
    private lateinit var server: ServerSocket
    private var port = 0

    // The server marks uid "keep-me" successful and "fail-me" a GENUINE failure (not a
    // duplicate) — so "fail-me" must stay pending and be retried.
    private val responseBody =
        """[{"success":["keep-me"],"failure":["null value in column post_time violates not-null constraint"]}]"""

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        NotiDatabase.get(ctx).clearAllTables()
        startServer()
        val s = Settings.get(ctx)
        s.bearerToken = "t"
        s.triggerMode = TriggerMode.MANUAL
        s.gzipEnabled = false
        s.retentionDays = 30
        s.webhookUrl = "http://127.0.0.1:$port/hook"
    }

    @After
    fun tearDown() {
        try { server.close() } catch (_: Exception) {}
        NotiDatabase.get(ctx).clearAllTables()
    }

    private fun startServer() {
        server = ServerSocket(0)
        port = server.localPort
        thread(isDaemon = true) {
            try {
                val sock = server.accept()
                sock.use {
                    val input = it.getInputStream()
                    // drain headers + body best-effort
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(4096)
                    Thread.sleep(50)
                    while (input.available() > 0) {
                        val n = input.read(tmp); if (n <= 0) break; buf.write(tmp, 0, n)
                    }
                    val bytes = responseBody.toByteArray(Charsets.UTF_8)
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$responseBody"
                    it.getOutputStream().write(resp.toByteArray())
                    it.getOutputStream().flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun entity(uid: String) = NotificationEntity(
        uid = uid, packageName = "com.example.chat", appLabel = "Chat",
        postTime = System.currentTimeMillis(), title = "t", text = "b",
        bigText = null, subText = null, category = "msg",
        sbnKey = "k-$uid", uploaded = 0, createdAt = System.currentTimeMillis()
    )

    @Test
    fun only_successful_uids_are_deleted_and_worker_retries() = runBlocking {
        val dao = NotiDatabase.get(ctx).notificationDao()
        dao.insert(entity("keep-me"))
        dao.insert(entity("fail-me"))
        assertEquals(2, dao.pendingCount())

        val result = TestListenableWorkerBuilder<UploadWorker>(ctx).build().doWork()

        // Failures present → worker asks for a retry.
        assertTrue("should retry when some records failed", result is ListenableWorker.Result.Retry)
        // Only "fail-me" remains pending; "keep-me" was deleted (marked uploaded).
        assertEquals("exactly one record should remain pending", 1, dao.pendingCount())
        val remaining = dao.pendingBatch(10)
        assertEquals(1, remaining.size)
        assertEquals("fail-me", remaining.first().uid)
    }
}
