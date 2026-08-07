package com.noti.sender

import android.content.Context
import android.util.Log
import com.noti.sender.config.SenderSettings
import com.noti.sender.fcm.FcmSender
import com.noti.sender.net.WebhookPoster
import com.noti.shared.MessageCrypto
import com.noti.shared.RelayMessage
import com.noti.shared.UploadBatch
import com.noti.shared.UploadItem
import com.noti.shared.epochMillisToIso
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Fans a captured message out to two destinations: an encrypted FCM push to noti, and a plaintext
 * POST to the n8n webhook (noti's schema). Each leg is independent and best-effort. The pure
 * builders are split out so they're unit-testable; [handle] does the blocking I/O.
 *
 * Logs only metadata (sender, length, HTTP codes) — never the body, which may be an OTP.
 */
object SenderPipeline {

    private const val TAG = "noti-sender"
    private val json = Json { encodeDefaults = true }

    /** The encrypted FCM data payload: AES-GCM over the serialized RelayMessage. */
    fun encryptForFcm(message: RelayMessage, keyBase64: String): String =
        MessageCrypto.encrypt(json.encodeToString(message), keyBase64)

    /** Maps a captured SMS into noti's webhook item shape. */
    fun smsToUploadItem(message: RelayMessage, deviceId: String, nowMillis: Long): UploadItem =
        UploadItem(
            deviceId = deviceId,
            uid = "$deviceId|sms|$nowMillis",
            pkg = "sms",
            appLabel = "SMS",
            postTime = epochMillisToIso(nowMillis),
            title = message.title,
            text = message.body,
            bigText = null,
            subText = null,
            category = "sms",
        )

    /**
     * Runs both legs (blocking network — call off the main thread). Returns false only when the FCM
     * leg failed in a transient way worth retrying (network, 5xx, 429); a bad token/auth (other 4xx)
     * is logged but not retried, since retrying won't fix it. The n8n leg is best-effort.
     */
    fun relay(context: Context, message: RelayMessage): Boolean {
        val s = SenderSettings.get(context)
        Log.i(TAG, "relaying SMS from '${message.title}' (${message.body.length} chars)")
        var retryable = false

        if (s.fcmEnabled && s.serviceAccountJson.isNotBlank() &&
            s.notiFcmToken.isNotBlank() && s.relayKey.isNotBlank()
        ) {
            try {
                val payload = encryptForFcm(message, s.relayKey)
                val res = FcmSender(s.serviceAccountJson).send(s.notiFcmToken, mapOf("payload" to payload))
                Log.i(TAG, "FCM leg: HTTP ${res.httpCode} ok=${res.ok}")
                if (!res.ok) retryable = res.httpCode == -1 || res.httpCode == 429 || res.httpCode in 500..599
            } catch (e: Exception) {
                Log.w(TAG, "FCM leg failed: ${e.message}")
                retryable = true
            }
        }

        if (s.n8nEnabled && s.n8nUrl.isNotBlank()) {
            try {
                val item = smsToUploadItem(message, s.deviceId, System.currentTimeMillis())
                val code = WebhookPoster.post(s.n8nUrl, s.n8nAuthHeaderName, s.n8nAuthValue(), UploadBatch(listOf(item)))
                Log.i(TAG, "n8n leg: HTTP $code")
            } catch (e: Exception) {
                Log.w(TAG, "n8n leg failed: ${e.message}")
            }
        }

        return !retryable
    }
}
