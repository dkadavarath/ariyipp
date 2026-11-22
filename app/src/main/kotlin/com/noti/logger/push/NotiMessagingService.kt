package com.noti.logger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noti.logger.config.Settings
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsCommandHandler
import com.noti.sender.sms.SmsSender
import com.noti.sender.sms.WebhookConfigHandler
import com.noti.sender.status.StatusPushHandler
import com.noti.sender.status.StatusReporter
import com.noti.shared.Diag
import com.noti.shared.Envelope
import com.noti.shared.Role
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * The single FCM entry point for the merged app, dispatching by role:
 *  - MAIN: an inbound push is a relayed message → decrypt + notify ([PushMessageHandler]).
 *  - COMPANION: an inbound push is a send-SMS command → decrypt + send via the SIM.
 * All decrypt/validate logic lives in the handlers (testable without Google Play Services).
 */
class NotiMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        when (Settings.get(applicationContext).role) {
            Role.COMPANION -> handleCommand(message)
            else -> {
                Diag.log("FCM push received")
                PushMessageHandler.handle(applicationContext, message.data)
            }
        }
    }

    /**
     * A push from Main, on the companion. Opened once (see Envelope) and handed to the handler for
     * its type - each used to decrypt and parse the payload again itself, so a heartbeat went
     * through seven decrypts before its own handler recognised it. Each handler still applies its
     * own gate (commands accepted, remote config accepted, status sharing on, stale versions).
     */
    private fun handleCommand(message: RemoteMessage) {
        val ctx = applicationContext
        val result = Envelope.openOrWhy(message.data, SenderSettings.get(ctx).relayKey, Wire.Direction.MAIN_TO_COMPANION)
        val opened = when (result) {
            is Envelope.Result.Ok -> result.opened
            is Envelope.Result.Failed -> {
                // Direction tags are a hard cut: an untagged push is from a Main on an older
                // version. Say so once rather than dropping its pushes silently.
                if (result.why == Envelope.Failure.UNTAGGED) com.noti.sender.PeerVersionNotice.onUntagged(ctx, peerIsMain = true)
                Diag.log("push from main DROPPED (${result.why.name.lowercase().replace('_', ' ')})")
                return
            }
        }
        com.noti.sender.PeerVersionNotice.onTagged(ctx)
        val sentAt = opened.sentAt
        val handled = when (val m = opened.message) {
            is WireMessage.Command -> onCommand(m, sentAt)
            is WireMessage.WebhookConfig -> WebhookConfigHandler.from(ctx, m)?.let { cfg ->
                Heartbeat.notePeerAlive(ctx, sentAt)
                WebhookConfigHandler.apply(ctx, cfg)
                Diag.log("webhook config applied from main (${if (cfg.enabled) "enabled" else "disabled"})")
            } != null
            is WireMessage.StatusRequest, is WireMessage.StatusConfig -> StatusPushHandler.from(ctx, m)?.let { msg ->
                Heartbeat.notePeerAlive(ctx, sentAt)
                when (msg) {
                    is WireMessage.StatusRequest -> StatusReporter.onRequest(ctx, msg.forSeconds)
                    is WireMessage.StatusConfig -> {
                        StatusPushHandler.apply(ctx, msg)
                        Diag.log("status thresholds applied from main (battery ${msg.batteryPct}%, wifi ${msg.wifiLossMinutes} min)")
                    }
                    else -> Unit
                }
            } != null
            is WireMessage.Token -> com.noti.sender.sms.HubTokenHandler.from(m)?.let { onMainEndpoint(it, sentAt) } != null
            is WireMessage.CatchUp -> com.noti.sender.sms.CatchUpHandler.from(m)?.let { onCatchUp(it, sentAt) } != null
            is WireMessage.Heartbeat -> { Heartbeat.onBeatReceived(ctx, m.request, m.off, sentAt); true }
            else -> false
        }
        if (!handled) Diag.log("push from main DROPPED (not accepted, or not a type the companion takes)")
    }

    /** A send-SMS command: checked (replay, age, commands on), then sent. False if it isn't one. */
    private fun onCommand(cmd: WireMessage.Command, sentAt: Long): Boolean =
        when (val outcome = SmsCommandHandler.check(applicationContext, cmd)) {
            is SmsCommandHandler.Outcome.Rejected -> {
                Heartbeat.notePeerAlive(applicationContext, sentAt)
                Diag.log("command REJECTED (msgId ${outcome.msgId}): ${outcome.reason}")
                if (outcome.report) {
                    com.noti.sender.AckWorker.enqueue(
                        applicationContext, outcome.msgId, WireMessage.DeliveryAck.FAILED, outcome.reason,
                    )
                }
                true
            }
            is SmsCommandHandler.Outcome.Accepted -> { sendCommand(outcome.cmd, sentAt); true }
            SmsCommandHandler.Outcome.NotCommand -> false
        }

    /** Main announcing a new push endpoint (its token rotated). Adopted unless it's an older copy;
     *  without it every relay, ack and heartbeat from here keeps going to a dead endpoint. */
    private fun onMainEndpoint(tok: WireMessage.Token, sentAt: Long) {
        val s = SenderSettings.get(applicationContext)
        val now = System.currentTimeMillis()
        if (sentAt <= 0) {
            com.noti.sender.PeerVersionNotice.onUnstamped(applicationContext, peerIsMain = true)
            return
        }
        if (!com.noti.shared.ReplayGuard.isNew(sentAt, s.notiTokenMark, now)) {
            Diag.log("main endpoint ignored - older than the one on file (a replayed copy)")
            return
        }
        s.notiTokenMark = com.noti.shared.ReplayGuard.next(s.notiTokenMark, sentAt, now)
        Heartbeat.notePeerAlive(applicationContext, sentAt)
        if (s.notiFcmToken != tok.endpoint) {
            s.notiFcmToken = tok.endpoint
            Diag.log("main endpoint updated - relays resume to the new hub token")
        }
    }

    /** Main lost relays in FCM's queue and wants them again. */
    private fun onCatchUp(req: WireMessage.CatchUp, sentAt: Long) {
        Heartbeat.notePeerAlive(applicationContext, sentAt)
        if (androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_SMS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val now = System.currentTimeMillis()
        val from = com.noti.sender.sms.CatchUpHandler.startMs(req, now)
        val s = SenderSettings.get(applicationContext)
        if (!com.noti.sender.sms.CatchUpHandler.mayRun(s.lastCatchUpAtMs, now)) {
            Diag.log("catch-up requested by main - ignored, one ran in the last 10 minutes")
            return
        }
        s.lastCatchUpAtMs = now
        val afterId = com.noti.sender.sms.SmsInbox.lastIdBefore(applicationContext, from)
        if (com.noti.sender.RepushWorker.startFrom(applicationContext, afterId)) {
            Diag.log("catch-up requested by main - re-sending SMS after id $afterId")
        } else {
            Diag.log("catch-up requested by main - a repush already running covers it")
        }
    }

    private fun sendCommand(cmd: WireMessage.Command, sentAt: Long) {
        Heartbeat.notePeerAlive(applicationContext, sentAt)
        // The companion's own limits (send rate, optional allowlist) come before the SIM.
        com.noti.sender.sms.SendGuard.check(applicationContext, cmd.to)?.let { reason ->
            Diag.log("command REFUSED (msgId ${cmd.msgId}): $reason")
            com.noti.sender.sms.SendGuard.notifyRefused(applicationContext, cmd.to, reason)
            com.noti.sender.AckWorker.enqueue(applicationContext, cmd.msgId, WireMessage.DeliveryAck.FAILED, reason)
            return
        }
        // The SMS first, then the acks from a background job: a blocking ack here could use up
        // the push handler's time before the SMS went out (see AckWorker).
        val status = try {
            SmsSender.send(applicationContext, cmd.to, cmd.body, cmd.sim, cmd.msgId)
            Diag.log("command → sent SMS to ${Diag.mask(cmd.to)} (slot ${cmd.sim}, ${cmd.body.length} chars)")
            WireMessage.DeliveryAck.RECEIVED
        } catch (e: Exception) {
            Diag.log("command → SMS send FAILED: ${e.message} (SEND_SMS granted?)")
            WireMessage.DeliveryAck.FAILED
        }
        com.noti.sender.AckWorker.enqueue(applicationContext, cmd.msgId, status)
    }

    /**
     * FCM dropped messages queued for this device (over 100 pending, or held past their lifetime).
     * On Main those were relays the companion already counted as sent, so ask for them again.
     * Commands dropped on the companion's side need nothing: Main shows them unacknowledged.
     */
    override fun onDeletedMessages() {
        Diag.log("FCM dropped messages queued for this phone while it was unreachable")
        if (Settings.get(applicationContext).role != Role.COMPANION) {
            com.noti.logger.work.CatchUpWorker.enqueue(applicationContext)
        }
    }

    /** Cache the refreshed token in both stores and re-announce it to the peer: the companion's so
     *  reverse-send keeps working, Main's so relays and heartbeats keep arriving - no re-pair. */
    override fun onNewToken(token: String) {
        val s = Settings.get(applicationContext)
        s.fcmToken = token
        SenderSettings.get(applicationContext).myFcmToken = token
        if (s.role == Role.COMPANION) {
            com.noti.sender.TokenAnnounceWorker.enqueue(applicationContext)
            Diag.log("FCM token refreshed - re-announcing endpoint to main")
        } else {
            com.noti.logger.work.HubTokenAnnounceWorker.enqueue(applicationContext)
            Diag.log("FCM token refreshed - re-announcing endpoint to the companion")
        }
    }
}
