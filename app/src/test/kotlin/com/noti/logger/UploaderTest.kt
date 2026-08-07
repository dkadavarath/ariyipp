package com.noti.logger

import com.noti.shared.UploadBatch
import com.noti.shared.UploadItem
import com.noti.logger.upload.UploadOutcome
import com.noti.logger.upload.Uploader
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class UploaderTest {

    private val json = Json { encodeDefaults = true }

    // Short timeouts so the "unreachable port" test finishes quickly.
    private fun uploader() = Uploader(
        json = json,
        connectTimeoutMs = 500,
        readTimeoutMs = 500
    )

    // Convenience wrapper preserving the old Bearer-token semantics for these tests:
    // a blank token omits the auth header (value blank). Defaults to gzip=true so the
    // existing gzip assertions exercise the compressed path.
    private fun Uploader.post(
        url: String,
        token: String,
        batch: UploadBatch,
        gzip: Boolean = true
    ): UploadOutcome =
        upload(url, "Authorization", if (token.isBlank()) "" else "Bearer $token", batch, gzip)

    private fun sampleBatch() = UploadBatch(
        batch = listOf(
            UploadItem(
                deviceId = "test-device",
                uid = "uid-1",
                pkg = "com.example.test",
                appLabel = "Test App",
                postTime = "2023-11-14T22:13:20Z",
                title = "Test title",
                text = "Test body",
                bigText = null,
                subText = null,
                category = "msg"
            )
        )
    )

    // ---- Minimal HTTP/1.1 server using raw ServerSocket ----

    private data class CapturedRequest(
        val method: String,
        val headers: Map<String, String>,   // lowercase header names
        val bodyBytes: ByteArray
    )

    /**
     * A single-use or multi-use fake HTTP server.
     * Captured requests are available via [requests] (blocking queue).
     * The response code returned to every request is [responseCode].
     */
    private class MockHttpServer(
        private val responseCode: Int,
        private val responseBody: String = """[{"success":[],"failure":[]}]"""
    ) : AutoCloseable {

        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort

        val requests = LinkedBlockingQueue<CapturedRequest>()

        private val thread = Thread { loop() }.also {
            it.isDaemon = true
            it.start()
        }

        private fun loop() {
            while (!serverSocket.isClosed) {
                try {
                    handle(serverSocket.accept())
                } catch (_: Exception) {
                    break
                }
            }
        }

        private fun handle(socket: Socket) = socket.use { s ->
            val stream = s.getInputStream()

            // Read request line
            val requestLine = readHeaderLine(stream)
            val method = requestLine.split(" ").firstOrNull() ?: ""

            // Read headers until blank line
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHeaderLine(stream)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            // Read body using Content-Length
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val n = stream.read(body, offset, contentLength - offset)
                if (n < 0) break
                offset += n
            }

            // Publish captured request BEFORE sending the response so that by
            // the time upload() returns (after receiving the response), the
            // request is already in the queue.
            requests.put(CapturedRequest(method, headers, body))

            val bodyBytes = responseBody.toByteArray(Charsets.UTF_8)
            val response =
                "HTTP/1.1 $responseCode \r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().let {
                it.write(response.toByteArray(Charsets.ISO_8859_1))
                it.write(bodyBytes)
                it.flush()
            }
        }

        /** Read one line from an HTTP/1.1 stream, stripping the trailing CR. */
        private fun readHeaderLine(stream: java.io.InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = stream.read()
                if (b == -1 || b.toChar() == '\n') break
                if (b.toChar() != '\r') sb.append(b.toChar())
            }
            return sb.toString()
        }

        override fun close() {
            serverSocket.close()
            thread.interrupt()
        }
    }

    /** Inline helper: starts a server, runs [block], closes the server. */
    private fun <T> withServer(
        responseCode: Int = 200,
        responseBody: String = """[{"success":[],"failure":[]}]""",
        block: (MockHttpServer) -> T
    ): T = MockHttpServer(responseCode, responseBody).use { block(it) }

    /** Poll the server's captured-request queue with a generous timeout. */
    private fun pollRequest(server: MockHttpServer): CapturedRequest =
        requireNotNull(server.requests.poll(5, TimeUnit.SECONDS)) {
            "Server did not receive a request within the timeout"
        }

    // ---- Request structure assertions ----

    @Test
    fun `request uses POST method`() = withServer { server ->
        uploader().post("http://localhost:${server.port}/", "testtoken", sampleBatch())
        assertEquals("POST", pollRequest(server).method)
    }

    @Test
    fun `request includes Authorization Bearer header`() = withServer { server ->
        uploader().post("http://localhost:${server.port}/", "testtoken", sampleBatch())
        assertEquals("Bearer testtoken", pollRequest(server).headers["authorization"])
    }

    @Test
    fun `request has Content-Encoding gzip header`() = withServer { server ->
        uploader().post("http://localhost:${server.port}/", "testtoken", sampleBatch())
        assertEquals("gzip", pollRequest(server).headers["content-encoding"])
    }

    @Test
    fun `GZIP-decoded body parses to the expected batch`() = withServer { server ->
        val batch = sampleBatch()
        uploader().post("http://localhost:${server.port}/", "testtoken", batch)
        val req = pollRequest(server)
        assertNotNull("Body should not be null", req.bodyBytes)
        assertTrue("Body should not be empty", req.bodyBytes.isNotEmpty())

        val decompressed = GZIPInputStream(req.bodyBytes.inputStream())
            .readBytes()
            .toString(Charsets.UTF_8)
        val decoded = json.decodeFromString<UploadBatch>(decompressed)
        assertEquals(batch, decoded)
    }

    @Test
    fun `plain mode sends no Content-Encoding and raw JSON body`() = withServer { server ->
        val batch = sampleBatch()
        uploader().post("http://localhost:${server.port}/", "tok", batch, gzip = false)
        val req = pollRequest(server)
        assertTrue(
            "Expected no gzip Content-Encoding in plain mode",
            req.headers["content-encoding"] == null
        )
        // Body should be readable JSON directly (not gzip-framed).
        val bodyText = req.bodyBytes.toString(Charsets.UTF_8)
        assertTrue("Body should be plain JSON: $bodyText", bodyText.trimStart().startsWith("{"))
        val decoded = json.decodeFromString<UploadBatch>(bodyText)
        assertEquals(batch, decoded)
    }

    @Test
    fun `no Authorization header when bearerToken is blank`() = withServer { server ->
        uploader().post("http://localhost:${server.port}/", "", sampleBatch())
        val authHeader = pollRequest(server).headers["authorization"]
        assertTrue(
            "Expected no auth header for blank token, got: $authHeader",
            authHeader == null || authHeader.isBlank()
        )
    }

    // ---- Response code mapping ----

    @Test
    fun `server 200 with success list returns Parsed with those uids`() {
        val body = """[{"success":["uid-1","uid-2"],"failure":[]}]"""
        withServer(200, body) { server ->
            val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
            assertTrue(result is UploadOutcome.Parsed)
            result as UploadOutcome.Parsed
            assertEquals(setOf("uid-1", "uid-2"), result.successUids)
            assertTrue(result.failures.isEmpty())
        }
    }

    @Test
    fun `server 200 with failures returns Parsed with failure messages`() {
        val body = """[{"success":["uid-1"],"failure":["Key (uid)=(uid-2) already exists."]}]"""
        withServer(200, body) { server ->
            val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
            assertTrue(result is UploadOutcome.Parsed)
            result as UploadOutcome.Parsed
            assertEquals(setOf("uid-1"), result.successUids)
            assertEquals(listOf("Key (uid)=(uid-2) already exists."), result.failures)
        }
    }

    @Test
    fun `server 200 with unparseable body returns Retry`() {
        withServer(200, "not json at all") { server ->
            val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
            assertEquals(UploadOutcome.Retry, result)
        }
    }

    @Test
    fun `server 201 with valid body returns Parsed`() {
        withServer(201, """[{"success":["u"],"failure":[]}]""") { server ->
            val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
            assertTrue(result is UploadOutcome.Parsed)
        }
    }

    @Test
    fun `server 500 returns Retry`() = withServer(500) { server ->
        val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
        assertEquals(UploadOutcome.Retry, result)
    }

    @Test
    fun `server 429 returns Retry`() = withServer(429) { server ->
        val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
        assertEquals(UploadOutcome.Retry, result)
    }

    @Test
    fun `server 408 returns Retry`() = withServer(408) { server ->
        val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
        assertEquals(UploadOutcome.Retry, result)
    }

    @Test
    fun `server 503 returns Retry`() = withServer(503) { server ->
        val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
        assertEquals(UploadOutcome.Retry, result)
    }

    @Test
    fun `server 404 returns ClientError with code 404`() = withServer(404) { server ->
        val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
        assertEquals(UploadOutcome.ClientError(404), result)
    }

    @Test
    fun `server 400 returns ClientError with code 400`() = withServer(400) { server ->
        val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
        assertEquals(UploadOutcome.ClientError(400), result)
    }

    @Test
    fun `server 401 returns ClientError with code 401`() = withServer(401) { server ->
        val result = uploader().post("http://localhost:${server.port}/", "tok", sampleBatch())
        assertEquals(UploadOutcome.ClientError(401), result)
    }

    // ---- Unreachable host ----

    @Test
    fun `unreachable port returns Retry`() {
        // Bind a ServerSocket just to obtain an ephemeral port, then close it immediately
        // so that connection attempts will be refused (fast fail, no timeout needed).
        val refusedPort = ServerSocket(0).use { it.localPort }
        val result = uploader().post("http://localhost:$refusedPort/", "tok", sampleBatch())
        assertEquals(UploadOutcome.Retry, result)
    }
}
