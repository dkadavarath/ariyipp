package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.shared.Diag
import com.noti.shared.FcmSender
import com.noti.shared.MessageCrypto
import com.noti.shared.SendCommand
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pushes an encrypted "send this SMS" command from noti to sndi (Phase B, reverse send): encrypts a
 * [SendCommand] with the shared key and sends it via FCM to sndi's token, using noti's copy of the
 * service-account key. Blocking network — call off the main thread.
 */
object NotiCommandSender {

    private val json = Json { encodeDefaults = true }

    /** True when noti has everything needed to push a command to sndi. */
    fun isConfigured(s: Settings): Boolean =
        s.serviceAccountJson.isNotBlank() && s.sndiFcmToken.isNotBlank() && s.relayKey.isNotBlank()

    /**
     * Returns true if FCM accepted the command. [sim] is the SIM slot sndi should send from
     * (0/1), or -1 for its default SIM.
     */
    fun send(context: Context, to: String, body: String, sim: Int = -1): Boolean {
        val s = Settings.get(context)
        if (!isConfigured(s)) {
            val missing = buildList {
                if (s.serviceAccountJson.isBlank()) add("service-account key")
                if (s.sndiFcmToken.isBlank()) add("ariy token")
                if (s.relayKey.isBlank()) add("shared key")
            }.joinToString(", ")
            Diag.log("compose FAILED — not configured: missing $missing (Settings → Relay)")
            return false
        }
        val payload = MessageCrypto.encrypt(json.encodeToString(SendCommand(to, body, sim)), s.relayKey)
        return try {
            val res = FcmSender(s.serviceAccountJson).send(s.sndiFcmToken, mapOf("payload" to payload))
            Diag.log(
                when {
                    res.ok -> "compose → HTTP 200 ✓ command sent to ariy"
                    res.httpCode == 401 || res.httpCode == 403 -> "compose → HTTP ${res.httpCode}: service-account key rejected"
                    res.httpCode == 404 -> "compose → HTTP 404: ariy token stale — re-pair"
                    else -> "compose → HTTP ${res.httpCode} (${res.detail.take(60)})"
                }
            )
            res.ok
        } catch (e: Exception) {
            Diag.log("compose → ERROR: ${e.message}")
            false
        }
    }
}
