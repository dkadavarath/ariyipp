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

    /** How long to wait for missing relay parts before giving up on an incomplete chunked body. */
    private const val PART_TTL_MS = 5 * 60_000L

    /** Sanity bound on a chunked relay's part count. A real long SMS splits into a handful of parts;
     *  this only guards against a malformed or hostile [WireMessage.Relay.parts] value. */
    private const val MAX_PARTS = 64

    /** Sanity bound on how many distinct in-flight multi-part groups are buffered at once - on top
     *  of [PART_TTL_MS] eviction, this caps memory even if many groups open within the TTL window. */
    private const val MAX_PENDING_GROUPS = 20

    /** In-flight chunks of multi-part relays, keyed by the message's identity. */
    private class PendingParts(val atMs: Long) {
        val parts = HashMap<Int, WireMessage.Relay>()
    }

    private val pendingRels = LinkedHashMap<String, PendingParts>()

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
            is WireMessage.DeliveryAck -> applyAck(context, wire)
            is WireMessage.Heartbeat -> {
                Heartbeat.onBeatReceived(context, wire.request)
                true
            }
            else -> false
        }
    }

    /** Updates a sent message's ticks. A stale/unknown msgId (e.g. the row was since deleted) is a
     *  quiet no-op, not an error - the ack just has nothing left to update. */
    private fun applyAck(context: Context, ack: WireMessage.DeliveryAck): Boolean {
        if (ack.msgId <= 0) return false
        val changed = try {
            NotiDatabase.get(context).relayedMessageDao().updateStatus(ack.msgId, ack.status)
        } catch (e: Exception) {
            0
        }
        Diag.log("delivery ack: msgId=${ack.msgId} status=${ack.status}" + if (changed == 0) " (no matching row)" else "")
        return true
    }

    /**
     * Buffers one chunk of a multi-part relay ([msg.parts] > 1) until all parts have arrived, then
     * returns the assembled message. Single-part messages pass straight through. Returns null while
     * parts are still missing (or for a stale/incomplete group that just got evicted).
     */
    @Synchronized
    private fun assemble(msg: WireMessage.Relay): WireMessage.Relay? {
        if (msg.parts <= 1) return msg

        // Evict groups we've waited too long on - FCM high-priority delivery is near-instant, so
        // anything this old means a part was lost; holding it forever would leak memory.
        val now = System.currentTimeMillis()
        pendingRels.entries.removeAll { now - it.value.atMs > PART_TTL_MS }
        // Cap on top of TTL eviction: bounds memory even if many groups open within the TTL window.
        while (pendingRels.size >= MAX_PENDING_GROUPS) {
            val oldestKey = pendingRels.entries.minByOrNull { it.value.atMs }?.key ?: break
            pendingRels.remove(oldestKey)
        }

        val key = msg.dedupe.ifBlank { "${msg.title}|${msg.time}" }
        val bucket = pendingRels.getOrPut(key) { PendingParts(now) }
        bucket.parts[msg.part] = msg
        if (bucket.parts.size < msg.parts) {
            Diag.log("inbound: buffered part ${msg.part + 1}/${msg.parts}")
            return null
        }

        pendingRels.remove(key)
        val first = bucket.parts[0] ?: bucket.parts.values.first()
        val body = (0 until msg.parts).joinToString("") { bucket.parts[it]?.body.orEmpty() }
        return first.copy(body = body, part = 0, parts = 1)
    }

    private fun showRelay(context: Context, settings: Settings, incoming: WireMessage.Relay): Boolean {
        if (!settings.pushInboundEnabled) {
            Diag.log("inbound push DROPPED - \"Receive relayed messages\" is OFF (Settings → Relay)")
            return false
        }
        if (incoming.parts > 1 && (incoming.parts > MAX_PARTS || incoming.part < 0 || incoming.part >= incoming.parts)) {
            Diag.log("inbound: dropped relay with invalid part=${incoming.part}/${incoming.parts}")
            return false
        }
        val msg = assemble(incoming) ?: return true // part buffered; nothing to show yet

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

        // Retention: chat history grows forever otherwise (the notifications table has its own
        // purge in UploadWorker). Same knob and semantics as uploads: days ≤ 0 ⇒ keep nothing.
        try {
            val cutoff = System.currentTimeMillis() - settings.retentionDays.coerceAtLeast(0) * 86_400_000L
            dao.purgeOlderThan(cutoff)
        } catch (_: Exception) {
            // best-effort
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
