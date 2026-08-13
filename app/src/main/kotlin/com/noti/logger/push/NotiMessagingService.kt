package com.noti.logger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noti.logger.config.Settings
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsCommandHandler
import com.noti.sender.sms.SmsSender
import com.noti.sender.sms.WebhookConfigHandler
import com.noti.shared.Diag
import com.noti.shared.Role

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
            try {
                SmsSender.send(applicationContext, cmd.to, cmd.body, cmd.sim)
                Diag.log("command → sent SMS to ${cmd.to} (slot ${cmd.sim}, ${cmd.body.length} chars)")
            } catch (e: Exception) {
                Diag.log("command → SMS send FAILED: ${e.message} (SEND_SMS granted?)")
            }
            return
        }
        // A webhook-config push from Main?
        WebhookConfigHandler.parse(applicationContext, message.data)?.let { cfg ->
            WebhookConfigHandler.apply(applicationContext, cfg)
            Diag.log("webhook config applied from Main (${if (cfg.enabled) "enabled" else "disabled"})")
            return
        }
        Diag.log("push from Main DROPPED (not accepted, wrong shared key, or unknown type)")
    }

    /** Cache the refreshed token in both stores; if this is the companion, re-announce it to Main so
     *  reverse-send keeps working with no copy-back. */
    override fun onNewToken(token: String) {
        val s = Settings.get(applicationContext)
        s.fcmToken = token
        SenderSettings.get(applicationContext).myFcmToken = token
        if (s.role == Role.COMPANION) {
            com.noti.sender.TokenAnnounceWorker.enqueue(applicationContext)
            Diag.log("FCM token refreshed — re-announcing endpoint to Main")
        } else {
            Diag.log("FCM token refreshed — re-pair if the companion needs the new hub token")
        }
    }
}
