package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import com.noti.shared.Diag
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
        if (!settings.pushInboundEnabled) {
            Diag.log("inbound push DROPPED — \"Receive relayed messages\" is OFF (Settings → Relay)")
            return false
        }
        val key = settings.relayKey.takeIf { it.isNotBlank() }
        if (key == null) {
            Diag.log("inbound push DROPPED — no shared key set (Settings → Relay)")
            return false
        }

        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            Diag.log("inbound push: DECRYPT FAILED — shared key doesn't match ariy's (re-pair)")
            return false
        }

        val msg = try {
            json.decodeFromString<RelayMessage>(plaintext)
        } catch (e: Exception) {
            // Tolerate a non-JSON plaintext by treating the whole thing as the body.
            RelayMessage(body = plaintext)
        }

        val title = msg.title.ifBlank { "Message" }

        // Drop a duplicate delivery (same SMS via the live relay and again via ariy's missed-sync).
        val dao = NotiDatabase.get(context).relayedMessageDao()
        if (msg.dedupe.isNotBlank()) {
            val dup = try { dao.countByDedupe(msg.dedupe) > 0 } catch (e: Exception) { false }
            if (dup) { Diag.log("inbound: duplicate ignored"); return true }
        }

        // Persist to the chat history (best-effort — a storage error must not block the notification).
        // Order by the SMS's real time (from the sender) so a delayed/synced message lands correctly
        // in the timeline; fall back to arrival time for older senders that don't send it.
        val (sender, sim) = RelayTitle.parse(title)
        val messageId = try {
            dao.insert(
                RelayedMessageEntity(
                    sender = sender,
                    sim = sim,
                    body = msg.body,
                    receivedAt = if (msg.time > 0) msg.time else System.currentTimeMillis(),
                    dedupe = msg.dedupe,
                )
            )
        } catch (e: Exception) {
            -1L // ignore; still notify below (without a deep-link target)
        }

        // A muted conversation still stores the message (it shows up as unread to catch up on later),
        // it just doesn't raise a notification.
        if (!settings.isMuted(sender)) {
            MessageNotifier.show(context, title, msg.body, sender, messageId)
            Diag.log("inbound: shown — from $sender (${msg.body.length} chars)")
        } else {
            Diag.log("inbound: stored (muted) — from $sender")
        }
        return true
    }
}
