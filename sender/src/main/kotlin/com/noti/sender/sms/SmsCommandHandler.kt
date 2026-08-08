package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import com.noti.shared.SendCommand
import kotlinx.serialization.json.Json

/**
 * Decrypts and validates a "send this SMS" command pushed from noti. Returns the command only when
 * commands are enabled, the shared key matches, and the payload is well-formed — otherwise null, so a
 * disabled/misconfigured device or a hostile message is a no-op. Free of Android telephony so it's
 * testable; the actual send is [SmsSender].
 */
object SmsCommandHandler {

    const val PAYLOAD_KEY = "payload"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(context: Context, data: Map<String, String>): SendCommand? {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return null

        val settings = SenderSettings.get(context)
        if (!settings.acceptCommands) return null
        val key = settings.relayKey.takeIf { it.isNotBlank() } ?: return null

        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            return null
        }
        return try {
            json.decodeFromString<SendCommand>(plaintext).takeIf { it.to.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
