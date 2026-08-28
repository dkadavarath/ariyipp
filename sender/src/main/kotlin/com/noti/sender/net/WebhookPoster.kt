package com.noti.sender.net

import com.noti.shared.UploadBatch
import com.noti.shared.WebhookUrlPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

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
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (authHeaderName.isNotBlank() && authHeaderValue.isNotBlank()) {
                conn.setRequestProperty(authHeaderName, authHeaderValue)
            }
            conn.outputStream.use { it.write(body) }
            conn.responseCode
        } catch (e: Exception) {
            -1
        }
    }
}
