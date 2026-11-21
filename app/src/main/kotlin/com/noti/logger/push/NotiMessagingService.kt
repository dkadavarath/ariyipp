package com.noti.logger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noti.logger.config.Settings
import com.noti.sender.SenderPipeline
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
        // A send-SMS command?
        SmsCommandHandler.parse(applicationContext, message.data)?.let { cmd ->
            Heartbeat.notePeerAlive(applicationContext)
            if (cmd.msgId > 0) {
                SenderPipeline.pushAckToIppu(applicationContext, cmd.msgId, WireMessage.DeliveryAck.RECEIVED)
            }
            try {
                SmsSender.send(applicationContext, cmd.to, cmd.body, cmd.sim, cmd.msgId)
                Diag.log("command → sent SMS to ${cmd.to} (slot ${cmd.sim}, ${cmd.body.length} chars)")
            } catch (e: Exception) {
                Diag.log("command → SMS send FAILED: ${e.message} (SEND_SMS granted?)")
                if (cmd.msgId > 0) {
                    SenderPipeline.pushAckToIppu(applicationContext, cmd.msgId, WireMessage.DeliveryAck.FAILED)
                }
            }
            return
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
        // A liveness heartbeat from Main?
        HeartbeatHandler.parse(applicationContext, message.data)?.let { hb ->
            Heartbeat.onBeatReceived(applicationContext, hb.request)
            return
        }
        Diag.log("push from main DROPPED (not accepted, wrong shared key, or unknown type)")
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
