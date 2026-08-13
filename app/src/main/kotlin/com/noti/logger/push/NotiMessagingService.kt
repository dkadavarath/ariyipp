package com.noti.logger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noti.logger.config.Settings
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsCommandHandler
import com.noti.sender.sms.SmsSender
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
        val cmd = SmsCommandHandler.parse(applicationContext, message.data)
        if (cmd == null) {
            Diag.log("command from Main DROPPED (accept-commands off, wrong shared key, or malformed)")
            return
        }
        try {
            SmsSender.send(applicationContext, cmd.to, cmd.body, cmd.sim)
            Diag.log("command → sent SMS to ${cmd.to} (slot ${cmd.sim}, ${cmd.body.length} chars)")
        } catch (e: Exception) {
            Diag.log("command → SMS send FAILED: ${e.message} (SEND_SMS granted?)")
        }
    }

    /** Cache the refreshed token in both stores so the active role has it. (Peer auto-announce comes
     *  with the pairing rework.) */
    override fun onNewToken(token: String) {
        Settings.get(applicationContext).fcmToken = token
        SenderSettings.get(applicationContext).myFcmToken = token
        Diag.log("FCM token refreshed — re-pair so the peer gets the new token")
    }
}
