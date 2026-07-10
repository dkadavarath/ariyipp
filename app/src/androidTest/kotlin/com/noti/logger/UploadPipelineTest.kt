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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Full upload pipeline E2E on-device: real Room DB + real Settings + real UploadWorker +
 * real HttpURLConnection/gzip Uploader, POSTing to an in-process HTTP server on 127.0.0.1.
 * Asserts the on-wire contract (Bearer auth, gzip body, snake_case JSON) AND that rows
 * flip to uploaded in the DB.
 */
@RunWith(AndroidJUnit4::class)
class UploadPipelineTest {

    private lateinit var ctx: Context
    private lateinit var settings: Settings

    // Captured from the incoming request.
    @Volatile private var reqMethod: String? = null
    @Volatile private var reqAuth: String? = null
    @Volatile private var reqEncoding: String? = null
    @Volatile private var reqBodyJson: String? = null
    private val received = CountDownLatch(1)

    private lateinit var server: ServerSocket
    private var port = 0

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        NotiDatabase.get(ctx).clearAllTables()

        settings = Settings.get(ctx)
        settings.bearerToken = "test-token-123"
        settings.triggerMode = TriggerMode.MANUAL
        settings.retentionDays = 30
        settings.gzipEnabled = true // this test asserts the gzip path end-to-end

        startServer()
        settings.webhookUrl = "http://127.0.0.1:$port/hook"
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
                    // Read headers line-by-line until blank line.
                    val headerText = StringBuilder()
                    var prev = -1
                    var cur: Int
                    var contentLength = 0
                    val headerBytes = ByteArrayOutputStream()
                    while (input.read().also { b -> cur = b } != -1) {
                        headerBytes.write(cur)
                        headerText.append(cur.toChar())
                        if (prev == '\r'.code && cur == '\n'.code &&
                            headerText.endsWith("\r\n\r\n")
                        ) break
                        prev = cur
                    }
                    val headers = headerText.toString().split("\r\n")
                    reqMethod = headers.firstOrNull()?.substringBefore(' ')
                    for (h in headers) {
                        val lower = h.lowercase()
                        when {
                            lower.startsWith("authorization:") ->
                                reqAuth = h.substringAfter(":").trim()
                            lower.startsWith("content-encoding:") ->
                                reqEncoding = h.substringAfter(":").trim()
                            lower.startsWith("content-length:") ->
                                contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    val body = ByteArray(contentLength)
                    var off = 0
                    while (off < contentLength) {
                        val r = input.read(body, off, contentLength - off)
                        if (r == -1) break
                        off += r
                    }
                    reqBodyJson = GZIPInputStream(body.inputStream()).readBytes().toString(Charsets.UTF_8)

                    // Echo both sent uids as successful so the worker deletes them.
                    val respBody = """[{"success":["u1","u2"],"failure":[]}]"""
                    val respBytes = respBody.toByteArray(Charsets.UTF_8)
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n$respBody"
                    it.getOutputStream().write(resp.toByteArray())
                    it.getOutputStream().flush()
                }
            } catch (_: Exception) {
                // server closed
            } finally {
                received.countDown()
            }
        }
    }

    private fun entity(uid: String, postTime: Long) = NotificationEntity(
        uid = uid,
        packageName = "com.example.chat",
        appLabel = "Chat",
        postTime = postTime,
        title = "Hello",
        text = "world",
        bigText = null,
        subText = null,
        category = "msg",
        sbnKey = "k-$uid",
        uploaded = 0,
        createdAt = postTime
    )

    @Test
    fun worker_uploads_pending_rows_and_marks_them_uploaded() = runBlocking {
        val dao = NotiDatabase.get(ctx).notificationDao()
        // Recent timestamps so the worker's 30-day retention purge does not evict them.
        val now = System.currentTimeMillis()
        dao.insert(entity("u1", now - 1000))
        dao.insert(entity("u2", now))
        assertEquals(2, dao.pendingCount())

        val worker = TestListenableWorkerBuilder<UploadWorker>(ctx).build()
        val result = worker.doWork()

        assertTrue("worker should succeed", result is ListenableWorker.Result.Success)
        assertTrue("server should have received a request", received.await(10, TimeUnit.SECONDS))

        assertEquals("POST", reqMethod)
        assertEquals("Bearer test-token-123", reqAuth)
        assertEquals("gzip", reqEncoding)

        val json = requireNotNull(reqBodyJson)
        assertTrue("has device_id", json.contains("\"device_id\""))
        assertTrue("has package key", json.contains("\"package\""))
        assertTrue("has uid u1", json.contains("u1"))
        assertTrue("has uid u2", json.contains("u2"))

        assertEquals("no rows should remain pending", 0, dao.pendingCount())
        assertEquals(2, dao.totalCount())
    }
}
