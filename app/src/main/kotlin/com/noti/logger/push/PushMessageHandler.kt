package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.shared.MessageCrypto
import com.noti.shared.RelayMessage
import kotlinx.serialization.json.Json

/**
 * Turns an inbound FCM data message into a local notification: pulls the ciphertext, decrypts it
 * with the pre-shared key, parses it, and posts it. Kept free of Firebase types so it can be tested
 * without Google Play Services (which only the FCM *transport* requires). Returns true iff a
 * notification was shown; every failure path drops silently so a bad/hostile message is a no-op.
 */
object PushMessageHandler {

    const val PAYLOAD_KEY = "payload"
    private val json = Json { ignoreUnknownKeys = true }

    fun handle(context: Context, data: Map<String, String>): Boolean {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return false

        val settings = Settings.get(context)
        if (!settings.pushInboundEnabled) return false
        val key = settings.relayKey.takeIf { it.isNotBlank() } ?: return false

        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            return false // wrong key, tampered, or malformed — drop
        }

        val msg = try {
            json.decodeFromString<RelayMessage>(plaintext)
        } catch (e: Exception) {
            // Tolerate a non-JSON plaintext by treating the whole thing as the body.
            RelayMessage(body = plaintext)
        }

        MessageNotifier.show(context, msg.title.ifBlank { "Message" }, msg.body)
        return true
    }
}
