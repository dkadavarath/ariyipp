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
            WebhookConfigHandler.apply(applicationContext, cfg)
            Diag.log("webhook config applied from main (${if (cfg.enabled) "enabled" else "disabled"})")
            return
        }
        // A liveness heartbeat from Main?
        HeartbeatHandler.parse(applicationContext, message.data)?.let { hb ->
            Heartbeat.onBeatReceived(applicationContext, hb.request)
            return
        }
        Diag.log("push from main DROPPED (not accepted, wrong shared key, or unknown type)")
    }

    /** Cache the refreshed token in both stores; if this is the companion, re-announce it to Main so
     *  reverse-send keeps working with no copy-back. */
    override fun onNewToken(token: String) {
        val s = Settings.get(applicationContext)
        s.fcmToken = token
        SenderSettings.get(applicationContext).myFcmToken = token
        if (s.role == Role.COMPANION) {
            com.noti.sender.TokenAnnounceWorker.enqueue(applicationContext)
            Diag.log("FCM token refreshed - re-announcing endpoint to main")
        } else {
            Diag.log("FCM token refreshed - re-pair if the companion needs the new hub token")
        }
    }
}
