package com.noti.sender.net

import com.noti.shared.UploadBatch
import com.noti.shared.WebhookUrlPolicy
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** POSTs an [UploadBatch] to the n8n webhook using noti's schema. Blocking - call off the main thread. */
object WebhookPoster {

    private val json = Json { encodeDefaults = true }

    /** Returns the HTTP status code, or -1 on a transport failure (including a non-https,
     *  non-loopback [url] - rejected before ever opening a connection, rather than relying solely
     *  on the network security config to block it, so a misconfigured URL fails the same clear way
     *  everywhere). */
    fun post(url: String, authHeaderName: String, authHeaderValue: String, batch: UploadBatch): Int {
        if (!WebhookUrlPolicy.isAllowed(url)) return -1
        val body = json.encodeToString(batch).toByteArray(Charsets.UTF_8)
        val conn = URL(url).openConnection() as HttpURLConnection
        // No disconnect(): closing the streams lets the connection return to the JVM keep-alive
        // pool, so the next post skips DNS+TCP+TLS.
        return try {
            conn.requestMethod = "POST"
            // Never follow a redirect: HttpURLConnection turns a 301/302 POST into a body-less GET
            // (which then "succeeds"), and carries a custom auth header to the new host. A 3xx is
            // returned as-is and the SMS is held (see SenderPipeline.classifyWebhook).
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (authHeaderName.isNotBlank() && authHeaderValue.isNotBlank()) {
                conn.setRequestProperty(authHeaderName, authHeaderValue)
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            // Drain and close the body: an unread response can't go back to the keep-alive pool,
            // so without this every post leaked a socket and paid a fresh TLS handshake.
            runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            }
            code
        } catch (e: Exception) {
            conn.disconnect()
            -1
        }
    }
}
