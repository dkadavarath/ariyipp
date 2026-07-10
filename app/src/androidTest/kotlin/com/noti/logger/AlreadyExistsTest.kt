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
 * "already exists" failures mean the record is already stored downstream (unique uid), so they
 * are treated as idempotent success: the record is deleted, and the worker does NOT retry.
 */
@RunWith(AndroidJUnit4::class)
class AlreadyExistsTest {

    private lateinit var ctx: Context
    private lateinit var server: ServerSocket
    private var port = 0

    // "new-one" succeeds; "dup-one" comes back as a duplicate → both should be deleted.
    private val responseBody =
        """[{"success":["new-one"],"failure":["Key (uid)=(dup-one) already exists."]}]"""

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
    fun duplicate_uid_is_deleted_and_worker_succeeds() = runBlocking {
        val dao = NotiDatabase.get(ctx).notificationDao()
        dao.insert(entity("new-one"))
        dao.insert(entity("dup-one"))
        assertEquals(2, dao.pendingCount())

        val result = TestListenableWorkerBuilder<UploadWorker>(ctx).build().doWork()

        // No genuine failures → success, and both records cleared from the outbox.
        assertTrue("should succeed (duplicate treated as stored)", result is ListenableWorker.Result.Success)
        assertEquals("nothing should remain pending", 0, dao.pendingCount())
    }
}
