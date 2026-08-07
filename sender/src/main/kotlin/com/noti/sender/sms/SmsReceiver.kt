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

        // onReceive is on the main thread; the relay does network I/O. goAsync() keeps the receiver
        // alive while a background thread finishes. (WorkManager would add delivery durability later.)
        val pending = goAsync()
        Thread {
            try {
                SenderPipeline.handle(context.applicationContext, relay)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
