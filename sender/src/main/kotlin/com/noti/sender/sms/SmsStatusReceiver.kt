package com.noti.sender.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.sender.SenderPipeline
import com.noti.shared.Diag
import com.noti.shared.WireMessage

/**
 * Fired by the [android.telephony.SmsManager] sent/delivery-report PendingIntents from [SmsSender].
 * Only our own PendingIntents trigger this (not exported), each carrying the [SmsSender.EXTRA_MSG_ID]
 * of the Command it's reporting on - looked up and acked back to Main over the encrypted channel.
 */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val msgId = intent.getLongExtra(SmsSender.EXTRA_MSG_ID, 0)
        if (msgId <= 0) return
        val status = when (intent.action) {
            SmsSender.ACTION_SMS_SENT ->
                if (resultCode == Activity.RESULT_OK) WireMessage.DeliveryAck.SMS_SENT else WireMessage.DeliveryAck.FAILED
            SmsSender.ACTION_SMS_DELIVERED ->
                // Not every carrier sends a delivery report; RESULT_CANCELED here just means "not
                // delivered (yet/ever) as far as the carrier told us" - not necessarily a failure, so
                // only ack forward on an actual positive confirmation and stay quiet otherwise.
                if (resultCode == Activity.RESULT_OK) WireMessage.DeliveryAck.SMS_DELIVERED else return
            else -> return
        }

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                SenderPipeline.pushAckToIppu(app, msgId, status)
                Diag.log("delivery ack sent: msgId=$msgId status=$status")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
