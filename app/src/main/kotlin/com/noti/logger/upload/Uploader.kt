package com.noti.logger.upload

import com.noti.shared.UploadBatch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

sealed interface UploadOutcome {
    /**
     * 2xx with a parseable body. Only [successUids] may be deleted from the outbox; every
     * other uid in the batch is retried. [failures] are the raw error messages (for the user).
     */
    data class Parsed(val successUids: Set<String>, val failures: List<String>) : UploadOutcome
    /** Transient (408/425/429, 5xx, network/timeout, unparseable 2xx) — retry silently. */
    object Retry : UploadOutcome
    /** 4xx transport rejection (auth/URL) — alert the user in-app, then retry. */
    data class ClientError(val code: Int) : UploadOutcome
}

class Uploader(
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000
) {

    /**
     * @param authHeaderName  the auth header to send (e.g. "Authorization" or a custom "key").
     * @param authHeaderValue the full header value (e.g. "Bearer <token>" or a raw token).
     *                        The header is omitted when either name or value is blank.
     */
    fun upload(
        url: String,
        authHeaderName: String,
        authHeaderValue: String,
        batch: UploadBatch,
        gzip: Boolean = false
    ): UploadOutcome {
        val jsonBytes = json.encodeToString(batch).toByteArray(Charsets.UTF_8)
        val body = if (gzip) gzip(jsonBytes) else jsonBytes

        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            if (authHeaderName.isNotBlank() && authHeaderValue.isNotBlank()) {
                conn.setRequestProperty(authHeaderName, authHeaderValue)
            }
            conn.setRequestProperty("Content-Type", "application/json")
            // Only advertise gzip when we actually gzip — many receivers (e.g. n8n)
            // don't decompress request bodies, so plain JSON is the safe default.
            if (gzip) conn.setRequestProperty("Content-Encoding", "gzip")

            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            when {
                code in 200..299 -> {
                    val responseBody = conn.inputStream.use { readCapped(it, MAX_RESPONSE_BYTES) }
                    parseResponse(responseBody)
                }
                // Transient: request timeout, too-early, rate-limit, and all server errors.
                code == 408 || code == 425 || code == 429 || code in 500..599 -> UploadOutcome.Retry
                // Other 4xx (400/401/403/404/…): transport-level rejection → alert + retry.
                code in 400..499 -> UploadOutcome.ClientError(code)
                else -> UploadOutcome.Retry
            }
        } catch (e: IOException) {
            UploadOutcome.Retry
        } catch (e: Exception) {
            UploadOutcome.Retry
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Parse `[{"success":[...],"failure":[...]}]`, merging all entries. Returns [UploadOutcome.Retry]
     * when the body can't be parsed — we must not delete records we can't confirm succeeded.
     */
    private fun parseResponse(body: String): UploadOutcome {
        return try {
            val entries: List<UploadResponseEntry> = json.decodeFromString(body)
            val success = entries.flatMap { it.success }.toSet()
            val failures = entries.flatMap { it.failure }
            UploadOutcome.Parsed(successUids = success, failures = failures)
        } catch (e: Exception) {
            UploadOutcome.Retry
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /**
     * Read at most [maxBytes] from [stream]. The expected response (per-uid success/failure
     * lists) is a few KB; capping prevents a hostile or misconfigured webhook from OOM-ing the
     * worker with an unbounded body.
     */
    private fun readCapped(stream: InputStream, maxBytes: Int): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = stream.read(chunk)
            if (read < 0) break
            val allowed = minOf(read, maxBytes - total)
            buffer.write(chunk, 0, allowed)
            total += allowed
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024 // 256 KB
    }
}
