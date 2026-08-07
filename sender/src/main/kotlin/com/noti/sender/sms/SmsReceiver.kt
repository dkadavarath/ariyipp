package com.noti.sender.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.noti.sender.RelayWorker

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

        // Hand off to WorkManager so delivery survives process death and retries on network loss,
        // rather than doing the network inline in the short-lived receiver.
        val request = OneTimeWorkRequestBuilder<RelayWorker>()
            .setInputData(workDataOf(RelayWorker.KEY_TITLE to relay.title, RelayWorker.KEY_BODY to relay.body))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }
}
