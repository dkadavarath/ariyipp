package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import com.noti.shared.Diag
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Turns an inbound FCM data message into a local notification (or applies a control message): pulls
 * the ciphertext, decrypts with the pre-shared key, decodes the typed wire message, and dispatches.
 * Kept free of Firebase types so it can be tested without Google Play Services. Returns true iff the
 * message was handled; every failure path drops silently so a bad/hostile message is a no-op.
 */
object PushMessageHandler {

    const val PAYLOAD_KEY = "payload"

    fun handle(context: Context, data: Map<String, String>): Boolean {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return false

        val settings = Settings.get(context)
        val key = settings.relayKey.takeIf { it.isNotBlank() }
        if (key == null) {
            Diag.log("inbound push DROPPED - no shared key set (Settings → Relay)")
            return false
        }

        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            Diag.log("inbound push: DECRYPT FAILED - shared key doesn't match the companion's (re-pair)")
            return false
        }

        val wire = try {
            Wire.decode(plaintext)
        } catch (e: Exception) {
            Diag.log("inbound push: unrecognized payload")
            return false
        }

        return when (wire) {
            is WireMessage.Token -> {
                // The companion announced its push endpoint - store it so reverse-send can reach it.
                settings.sndiFcmToken = wire.endpoint
                Diag.log("companion endpoint received - reverse-send ready")
                true
            }
            is WireMessage.Relay -> showRelay(context, settings, wire)
            is WireMessage.Heartbeat -> {
                Heartbeat.onBeatReceived(context, wire.request)
                true
            }
            else -> false
        }
    }

    private fun showRelay(context: Context, settings: Settings, msg: WireMessage.Relay): Boolean {
        if (!settings.pushInboundEnabled) {
            Diag.log("inbound push DROPPED - \"Receive relayed messages\" is OFF (Settings → Relay)")
            return false
        }

        val title = msg.title.ifBlank { "Message" }

        // Drop a duplicate delivery (same SMS via the live relay and again via ariy's missed-sync).
        val dao = NotiDatabase.get(context).relayedMessageDao()
        if (msg.dedupe.isNotBlank()) {
            val dup = try { dao.countByDedupe(msg.dedupe) > 0 } catch (e: Exception) { false }
            if (dup) { Diag.log("inbound: duplicate ignored"); return true }
        }

        // Persist to the chat history (best-effort - a storage error must not block the notification).
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
            Diag.log("inbound: shown - from $sender (${msg.body.length} chars)")
        } else {
            Diag.log("inbound: stored (muted) - from $sender")
        }
        return true
    }
}
