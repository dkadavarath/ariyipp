package com.noti.logger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noti.logger.config.Settings
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.HeartbeatHandler
import com.noti.sender.sms.SmsCommandHandler
import com.noti.sender.sms.SmsSender
import com.noti.sender.sms.WebhookConfigHandler
import com.noti.sender.status.StatusPushHandler
import com.noti.sender.status.StatusReporter
import com.noti.shared.Diag
import com.noti.shared.Role
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

    private fun handleCommand(message: RemoteMessage) {
        // Direction tags (a hard cut): a push without one is from a Main on an older version, and
        // every handler below will refuse it. Say so once rather than dropping pushes silently.
        when (directionState(message.data)) {
            DirectionState.UNTAGGED -> { com.noti.sender.PeerVersionNotice.onUntagged(applicationContext, peerIsMain = true); return }
            DirectionState.TAGGED -> com.noti.sender.PeerVersionNotice.onTagged(applicationContext)
            DirectionState.UNREADABLE -> Unit
        }
        // A send-SMS command?
        when (val outcome = SmsCommandHandler.check(applicationContext, message.data)) {
            is SmsCommandHandler.Outcome.Rejected -> {
                Heartbeat.notePeerAlive(applicationContext)
                Diag.log("command REJECTED (msgId ${outcome.msgId}): ${outcome.reason}")
                if (outcome.report) {
                    com.noti.sender.AckWorker.enqueue(
                        applicationContext, outcome.msgId, WireMessage.DeliveryAck.FAILED, outcome.reason,
                    )
                }
                return
            }
            is SmsCommandHandler.Outcome.Accepted -> {
                sendCommand(outcome.cmd)
                return
            }
            SmsCommandHandler.Outcome.NotCommand -> Unit
        }
        // A webhook-config push from Main?
        WebhookConfigHandler.parse(applicationContext, message.data)?.let { cfg ->
            Heartbeat.notePeerAlive(applicationContext)
            WebhookConfigHandler.apply(applicationContext, cfg)
            Diag.log("webhook config applied from main (${if (cfg.enabled) "enabled" else "disabled"})")
            return
        }
        // A device-status request or threshold config from Main?
        StatusPushHandler.parse(applicationContext, message.data)?.let { msg ->
            Heartbeat.notePeerAlive(applicationContext)
            when (msg) {
                is WireMessage.StatusRequest -> StatusReporter.onRequest(applicationContext, msg.forSeconds)
                is WireMessage.StatusConfig -> {
                    StatusPushHandler.apply(applicationContext, msg)
                    Diag.log("status thresholds applied from main (battery ${msg.batteryPct}%, wifi ${msg.wifiLossMinutes} min)")
                }
                else -> Unit
            }
            return
        }
        // Main announcing a new push endpoint (its token rotated)? Adopt it, or every relay, ack and
        // heartbeat from here keeps going to a dead endpoint while Main → companion still works.
        com.noti.sender.sms.HubTokenHandler.parse(applicationContext, message.data)?.let { tok ->
            Heartbeat.notePeerAlive(applicationContext)
            val s = SenderSettings.get(applicationContext)
            if (s.notiFcmToken != tok.endpoint) {
                s.notiFcmToken = tok.endpoint
                Diag.log("main endpoint updated - relays resume to the new hub token")
            }
            return
        }
        // Main lost relays in FCM's queue and wants them again?
        com.noti.sender.sms.CatchUpHandler.parse(applicationContext, message.data)?.let { req ->
            Heartbeat.notePeerAlive(applicationContext)
            val from = com.noti.sender.sms.CatchUpHandler.startMs(req, System.currentTimeMillis())
            if (androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val s = SenderSettings.get(applicationContext)
                val now = System.currentTimeMillis()
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
            return
        }
        // A liveness heartbeat from Main?
        HeartbeatHandler.parse(applicationContext, message.data)?.let { hb ->
            Heartbeat.onBeatReceived(applicationContext, hb.request, hb.off)
            return
        }
        Diag.log("push from main DROPPED (not accepted, wrong shared key, or unknown type)")
    }

    private enum class DirectionState { TAGGED, UNTAGGED, UNREADABLE }

    /** Whether a push from Main decrypts, and carries a direction tag. */
    private fun directionState(data: Map<String, String>): DirectionState {
        val ciphertext = data["payload"]?.takeIf { it.isNotBlank() } ?: return DirectionState.UNREADABLE
        val key = SenderSettings.get(applicationContext).relayKey.takeIf { it.isNotBlank() } ?: return DirectionState.UNREADABLE
        val plain = try { com.noti.shared.MessageCrypto.decrypt(ciphertext, key) } catch (e: Exception) { return DirectionState.UNREADABLE }
        return if (com.noti.shared.Wire.isUntagged(plain)) DirectionState.UNTAGGED else DirectionState.TAGGED
    }

    private fun sendCommand(cmd: WireMessage.Command) {
        Heartbeat.notePeerAlive(applicationContext)
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
            Diag.log("command → sent SMS to ${cmd.to} (slot ${cmd.sim}, ${cmd.body.length} chars)")
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
