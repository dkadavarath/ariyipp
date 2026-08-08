package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
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

        val title = msg.title.ifBlank { "Message" }

        // Persist to the chat history (best-effort — a storage error must not block the notification).
        val (sender, sim) = RelayTitle.parse(title)
        val messageId = try {
            NotiDatabase.get(context).relayedMessageDao().insert(
                RelayedMessageEntity(
                    sender = sender,
                    sim = sim,
                    body = msg.body,
                    receivedAt = System.currentTimeMillis(),
                )
            )
        } catch (e: Exception) {
            -1L // ignore; still notify below (without a deep-link target)
        }

        // A muted conversation still stores the message (it shows up as unread to catch up on later),
        // it just doesn't raise a notification.
        if (!settings.isMuted(sender)) {
            MessageNotifier.show(context, title, msg.body, sender, messageId)
        }
        return true
    }
}
