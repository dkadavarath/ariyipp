package com.noti.sender

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsCommandHandler
import com.noti.sender.sms.SmsSender

/**
 * Receives encrypted "send this SMS" commands from noti and sends them via the SIM. Thin — the
 * decrypt/validate logic is in [SmsCommandHandler]; the send is [SmsSender]. Also caches this
 * device's FCM token so noti knows where to push.
 */
class SmsCommandService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val cmd = SmsCommandHandler.parse(applicationContext, message.data)
        if (cmd == null) {
            com.noti.shared.Diag.log("command from ippu DROPPED (accept-commands off, wrong shared key, or malformed)")
            return
        }
        try {
            SmsSender.send(applicationContext, cmd.to, cmd.body, cmd.sim)
            Log.i(TAG, "sent SMS to '${cmd.to}' on slot ${cmd.sim} (${cmd.body.length} chars)")
            com.noti.shared.Diag.log("command → sent SMS to ${cmd.to} (slot ${cmd.sim}, ${cmd.body.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "SMS send failed: ${e.message}")
            com.noti.shared.Diag.log("command → SMS send FAILED: ${e.message} (SEND_SMS granted?)")
        }
    }

    override fun onNewToken(token: String) {
        SenderSettings.get(applicationContext).myFcmToken = token
        com.noti.shared.Diag.log("FCM token refreshed — RE-PAIR so ippu gets the new token")
    }

    private companion object {
        const val TAG = "noti-sender"
    }
}
