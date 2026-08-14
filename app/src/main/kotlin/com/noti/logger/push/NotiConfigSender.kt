package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.shared.Diag
import com.noti.shared.FcmSender
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Pushes the Main-authored companion webhook config to the companion over the encrypted channel. The
 * companion overwrites its local webhook with it (unless it's set to ignore remote config). Blocking
 * network - call off the main thread.
 */
object NotiConfigSender {

    fun pushWebhook(context: Context): Boolean {
        val s = Settings.get(context)
        if (s.serviceAccountJson.isBlank() || s.sndiFcmToken.isBlank() || s.relayKey.isBlank()) {
            Diag.log("push webhook FAILED - not paired with a companion yet")
            return false
        }
        // The one shared webhook: Main's own webhook, pushed to the companion so its SMS go there too.
        val cfg = WireMessage.WebhookConfig(
            enabled = s.webhookUrl.isNotBlank(),
            url = s.webhookUrl,
            authHeaderName = s.authHeaderName,
            authHeaderPrefix = s.authHeaderPrefix,
            authToken = s.bearerToken,
        )
        val payload = MessageCrypto.encrypt(Wire.encode(cfg), s.relayKey)
        return try {
            val res = FcmSender(s.serviceAccountJson).send(s.sndiFcmToken, mapOf("payload" to payload))
            Diag.log(if (res.ok) "webhook config pushed to companion" else "push webhook → HTTP ${res.httpCode} (${res.detail.take(50)})")
            res.ok
        } catch (e: Exception) {
            Diag.log("push webhook → ERROR: ${e.message}")
            false
        }
    }
}
