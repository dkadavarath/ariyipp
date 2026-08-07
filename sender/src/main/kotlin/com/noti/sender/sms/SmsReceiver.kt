package com.noti.sender.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.noti.sender.SenderPipeline

/**
 * Observes incoming SMS (via RECEIVE_SMS; not the default SMS app). Parses the broadcast into parts,
 * assembles them, and hands the result to [SenderPipeline]. Kept thin — the parsing logic lives in
 * the pure, tested [SmsAssembler].
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val parts = messages.map { sms ->
            SmsPart(
                from = sms.displayOriginatingAddress ?: sms.originatingAddress,
                body = sms.displayMessageBody ?: sms.messageBody.orEmpty()
            )
        }

        val relay = SmsAssembler.assemble(parts) ?: return
        SenderPipeline.handle(context.applicationContext, relay)
    }
}
