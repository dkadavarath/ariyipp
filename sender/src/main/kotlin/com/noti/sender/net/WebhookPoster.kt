package com.noti.sender.net

import com.noti.shared.UploadBatch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** POSTs an [UploadBatch] to the n8n webhook using noti's schema. Blocking - call off the main thread. */
object WebhookPoster {

    private val json = Json { encodeDefaults = true }

    /** Returns the HTTP status code, or -1 on a transport failure. */
    fun post(url: String, authHeaderName: String, authHeaderValue: String, batch: UploadBatch): Int {
        val body = json.encodeToString(batch).toByteArray(Charsets.UTF_8)
        val conn = URL(url).openConnection() as HttpURLConnection
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
        } finally {
            conn.disconnect()
        }
    }
}
