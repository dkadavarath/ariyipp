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
        val cmd = SmsCommandHandler.parse(applicationContext, message.data) ?: return
        try {
            SmsSender.send(applicationContext, cmd.to, cmd.body)
            Log.i(TAG, "sent SMS to '${cmd.to}' (${cmd.body.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "SMS send failed: ${e.message}")
        }
    }

    override fun onNewToken(token: String) {
        SenderSettings.get(applicationContext).myFcmToken = token
    }

    private companion object {
        const val TAG = "noti-sender"
    }
}
