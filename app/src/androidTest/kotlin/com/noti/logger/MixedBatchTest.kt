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
 * Full three-way mix in a single batch:
 *   - "ok-1"  → success            → deleted
 *   - "dup-1" → already exists      → deleted (stored downstream)
 *   - "bad-1" → genuine failure     → stays pending, retried
 * Only the genuine failure survives; the worker retries.
 */
@RunWith(AndroidJUnit4::class)
class MixedBatchTest {

    private lateinit var ctx: Context
    private lateinit var server: ServerSocket
    private var port = 0

    private val responseBody = """
        [{"success":["ok-1"],
          "failure":["Key (uid)=(dup-1) already exists.",
                     "null value in column post_time violates not-null constraint"]}]
    """.trimIndent()

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
                    val tmp = ByteArray(4096)
                    Thread.sleep(50)
                    while (input.available() > 0) { if (input.read(tmp) <= 0) break }
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
    fun success_and_duplicate_deleted_only_genuine_failure_retries() = runBlocking {
        val dao = NotiDatabase.get(ctx).notificationDao()
        dao.insert(entity("ok-1"))
        dao.insert(entity("dup-1"))
        dao.insert(entity("bad-1"))
        assertEquals(3, dao.pendingCount())

        val result = TestListenableWorkerBuilder<UploadWorker>(ctx).build().doWork()

        assertTrue("genuine failure present → retry", result is ListenableWorker.Result.Retry)
        val remaining = dao.pendingBatch(10)
        assertEquals("only the genuine failure remains", 1, remaining.size)
        assertEquals("bad-1", remaining.first().uid)
    }
}
