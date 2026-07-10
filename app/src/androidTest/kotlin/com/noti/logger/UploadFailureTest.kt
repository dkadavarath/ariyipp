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

    private fun startServerReturning(code: Int) {
        server = ServerSocket(0)
        port = server.localPort
        thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    sock.use {
                        // drain request, then respond with the failure code
                        val input = it.getInputStream()
                        val buf = ByteArray(4096)
                        // best-effort read of available bytes
                        if (input.available() > 0) input.read(buf)
                        val resp = "HTTP/1.1 $code Client Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
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
}
