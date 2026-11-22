package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.logger.data.ChatRetention
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import com.noti.shared.Diag
import com.noti.shared.Envelope
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Turns an inbound FCM data message into a local notification (or applies a control message): pulls
 * the ciphertext, decrypts with the pre-shared key, decodes the typed wire message, and dispatches.
 * Kept free of Firebase types so it can be tested without Google Play Services. Returns true iff the
 * message was handled; every failure path drops silently so a bad/hostile message is a no-op.
 */
object PushMessageHandler {

    const val PAYLOAD_KEY = Envelope.PAYLOAD_KEY

    /** How long to wait for missing relay parts before giving up on an incomplete chunked body.
     *  The parts are on disk (see [RelayParts]), so this can outlast a Doze window. */
    private const val PART_TTL_MS = 30 * 60_000L

    /** Sanity bound on a chunked relay's part count. A real long SMS splits into a handful of parts;
     *  this only guards against a malformed or hostile [WireMessage.Relay.parts] value. */
    private const val MAX_PARTS = 64

    /** Sanity bound on how many distinct in-flight multi-part groups are buffered at once - on top
     *  of [PART_TTL_MS] eviction, this caps memory even if many groups open within the TTL window. */
    private const val MAX_PENDING_GROUPS = 20

    fun handle(context: Context, data: Map<String, String>): Boolean {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return false

        val settings = Settings.get(context)
        val key = settings.relayKey.takeIf { it.isNotBlank() }
        if (key == null) {
            Diag.log("inbound push DROPPED - no shared key set (Settings → Relay)")
            return false
        }

        val opened = when (val r = Envelope.openOrWhy(data, key, Wire.Direction.COMPANION_TO_MAIN)) {
            is Envelope.Result.Ok -> r.opened
            is Envelope.Result.Failed -> {
                when (r.why) {
                    Envelope.Failure.WRONG_KEY -> {
                        Diag.log("inbound push: DECRYPT FAILED - shared key doesn't match the companion's (re-pair)")
                        com.noti.logger.alert.Alerter.alertKeyMismatch(context)
                    }
                    Envelope.Failure.UNTAGGED -> com.noti.sender.PeerVersionNotice.onUntagged(context, peerIsMain = false)
                    Envelope.Failure.WRONG_DIRECTION ->
                        Diag.log("inbound push REFUSED - tagged main → companion: a message reflected back to this phone")
                    else -> Diag.log("inbound push: unrecognized payload")
                }
                return false
            }
        }
        val wire = opened.message
        com.noti.sender.PeerVersionNotice.onTagged(context)

        // It decrypted with the shared key, so the companion sent it: proof of life on its own, if
        // it's newer than the last thing heard (a replayed copy decrypts too).
        val sentAt = opened.sentAt
        if (wire !is WireMessage.Heartbeat && wire !is WireMessage.Token) Heartbeat.notePeerAlive(context, sentAt)

        return when (wire) {
            is WireMessage.Token -> onCompanionEndpoint(context, settings, wire, sentAt)
            is WireMessage.Relay -> showRelay(context, settings, wire, ciphertext, key)
            is WireMessage.DeliveryAck -> applyAck(context, wire)
            is WireMessage.Heartbeat -> {
                Heartbeat.onBeatReceived(context, wire.request, wire.off, sentAt)
                true
            }
            is WireMessage.StatusReport -> {
                CompanionStatus.onReport(context, wire)
                true
            }
            else -> false
        }
    }

    /** The companion announced its push endpoint: stored so reverse-send can reach it - unless it's
     *  an older copy, since replaying one would point commands at a dead endpoint. */
    private fun onCompanionEndpoint(context: Context, settings: Settings, wire: WireMessage.Token, sentAt: Long): Boolean {
        val now = System.currentTimeMillis()
        if (sentAt <= 0) {
            com.noti.sender.PeerVersionNotice.onUnstamped(context, peerIsMain = false)
            return false
        }
        if (!com.noti.shared.ReplayGuard.isNew(sentAt, settings.sndiTokenMark, now)) {
            Diag.log("companion endpoint ignored - older than the one on file (a replayed copy)")
            return false
        }
        settings.sndiTokenMark = com.noti.shared.ReplayGuard.next(settings.sndiTokenMark, sentAt, now)
        Heartbeat.notePeerAlive(context, sentAt)
        settings.sndiFcmToken = wire.endpoint
        Diag.log("companion endpoint received - reverse-send ready")
        return true
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
        Diag.log("delivery ack: msgId=${ack.msgId} status=${ack.status}" +
            (if (ack.reason.isNotBlank()) " - companion refused: ${ack.reason}" else "") +
            if (changed == 0) " (no matching row)" else "")
        return true
    }

    /**
     * Buffers one chunk of a multi-part relay ([msg.parts] > 1) until all parts have arrived, then
     * returns the assembled message. Single-part messages pass straight through. Returns null while
     * parts are still missing (or for a stale/incomplete group that just got evicted).
     *
     * The parts wait on disk, as the [ciphertext] they arrived in: kept in memory only, a process
     * death between two parts (Android reclaims a background app at will) lost the whole message.
     * Ciphertext, so nothing readable is written outside the encrypted database.
     */
    @Synchronized
    private fun assemble(context: Context, msg: WireMessage.Relay, ciphertext: String, key: String): WireMessage.Relay? {
        if (msg.parts <= 1) return msg
        val store = RelayParts(context)
        val now = System.currentTimeMillis()
        store.evict(now, PART_TTL_MS, MAX_PENDING_GROUPS)

        val group = msg.dedupe.ifBlank { "${msg.title}|${msg.time}" }
        val stored = store.put(group, msg.part, ciphertext, now)
        if (stored.size < msg.parts) {
            Diag.log("inbound: buffered part ${msg.part + 1}/${msg.parts}")
            return null
        }
        store.remove(group)
        val parts = HashMap<Int, WireMessage.Relay>()
        for ((index, text) in stored) {
            val part = Envelope.open(mapOf(PAYLOAD_KEY to text), key, Wire.Direction.COMPANION_TO_MAIN)?.message as? WireMessage.Relay
            if (part == null || part.parts != msg.parts) {
                // Waited across a re-pair (another key), or not the same message after all.
                Diag.log("inbound: a buffered part couldn't be read back - long message dropped")
                return null
            }
            parts[index] = part
        }
        val first = parts[0] ?: parts.values.first()
        val body = (0 until msg.parts).joinToString("") { parts[it]?.body.orEmpty() }
        return first.copy(body = body, part = 0, parts = 1)
    }

    private fun showRelay(context: Context, settings: Settings, incoming: WireMessage.Relay, ciphertext: String, key: String): Boolean {
        if (!settings.pushInboundEnabled) {
            Diag.log("inbound push DROPPED - \"Receive relayed messages\" is OFF (Settings → Relay)")
            return false
        }
        if (incoming.parts > 1 && (incoming.parts > MAX_PARTS || incoming.part < 0 || incoming.part >= incoming.parts)) {
            Diag.log("inbound: dropped relay with invalid part=${incoming.part}/${incoming.parts}")
            return false
        }
        val msg = assemble(context, incoming, ciphertext, key) ?: return true // part buffered; nothing to show yet

        val title = msg.title.ifBlank { "Message" }

        // Drop a duplicate delivery (same SMS via the live relay and again via a repush or
        // catch-up) - but not a separate SMS that merely says the same thing (see countCopies).
        val dao = NotiDatabase.get(context).relayedMessageDao()
        if (msg.dedupe.isNotBlank()) {
            val dup = try {
                if (msg.time > 0) dao.countCopies(msg.dedupe, msg.time) > 0 else dao.countByDedupe(msg.dedupe) > 0
            } catch (e: Exception) { false }
            if (dup) { Diag.log("inbound: duplicate ignored"); return true }
        }

        // Order by the SMS's real time (from the sender) so a delayed/synced message lands correctly
        // in the timeline; fall back to arrival time for older senders that don't send it.
        val now = System.currentTimeMillis()
        val receivedAt = if (msg.time > 0) msg.time else now
        // The fields themselves when the companion sends them; the title split only for one that
        // doesn't (an older version).
        val (sender, sim) = if (msg.from.isNotBlank()) msg.from to msg.sim else RelayTitle.parse(title)

        // Already past the chat-history window: storing it would only get it purged straight back
        // out, and notifying for it would announce a message that then isn't in the chat.
        if (!ChatRetention.keeps(receivedAt, settings.chatRetentionDays, now)) {
            Diag.log("inbound: skipped - older than the chat history window (from ${Diag.mask(sender)})")
            return true
        }

        // Persist to the chat history (best-effort - a storage error must not block the notification).
        val messageId = try {
            dao.insert(
                RelayedMessageEntity(
                    sender = sender,
                    sim = sim,
                    body = msg.body,
                    receivedAt = receivedAt,
                    dedupe = msg.dedupe,
                )
            )
        } catch (e: Exception) {
            -1L // ignore; still notify below (without a deep-link target)
        }

        // A rule may be about this sender: run the rulebook now rather than at the next scan.
        if (messageId > 0) {
            try { com.noti.logger.reminder.ReminderTrigger.onSms(context, sender) } catch (_: Exception) {}
        }

        // Chat retention has its own knob, default forever - it used to share the notification-
        // capture setting, which silently truncated chat history to that window.
        ChatRetention.cutoff(settings.chatRetentionDays, now)?.let { cutoff ->
            try {
                dao.purgeOlderThan(cutoff)
            } catch (_: Exception) {
                // best-effort
            }
        }

        // A muted conversation still stores the message (it shows up as unread to catch up on later),
        // it just doesn't raise a notification. Nor does a backfill: an SMS that was hours or months
        // old by the time it got here (a repush, a catch-up) is stored unread, quietly.
        if (!ChatRetention.notifies(receivedAt, now)) {
            Diag.log("inbound: stored quietly (backfill) - from ${Diag.mask(sender)}")
        } else if (!settings.isMuted(sender)) {
            MessageNotifier.show(context, title, msg.body, sender, messageId)
            Diag.log("inbound: shown - from ${Diag.mask(sender)} (${msg.body.length} chars)")
        } else {
            Diag.log("inbound: stored (muted) - from ${Diag.mask(sender)}")
        }
        return true
    }
}
