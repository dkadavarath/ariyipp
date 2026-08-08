package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
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

    /** Returns true if FCM accepted the command. */
    fun send(context: Context, to: String, body: String): Boolean {
        val s = Settings.get(context)
        if (!isConfigured(s)) return false
        val payload = MessageCrypto.encrypt(json.encodeToString(SendCommand(to, body)), s.relayKey)
        return try {
            FcmSender(s.serviceAccountJson).send(s.sndiFcmToken, mapOf("payload" to payload)).ok
        } catch (e: Exception) {
            false
        }
    }
}
