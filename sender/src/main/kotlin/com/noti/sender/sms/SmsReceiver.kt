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
import com.noti.sender.config.SenderSettings

/**
 * Observes incoming SMS (via RECEIVE_SMS; not the default SMS app). Parses the broadcast into parts,
 * assembles them, and hands the result to [SenderPipeline]. Kept thin — the parsing logic lives in
 * the pure, tested [SmsAssembler].
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val received = System.currentTimeMillis()
        val sim = SenderSettings.get(context).simName(resolveSlot(intent))
        val parts = messages.map { sms ->
            SmsPart(
                from = sms.displayOriginatingAddress ?: sms.originatingAddress,
                body = sms.displayMessageBody ?: sms.messageBody.orEmpty(),
                sentMillis = sms.timestampMillis
            )
        }

        val sms = SmsAssembler.assemble(parts, received, sim) ?: return

        // Hand off to WorkManager so delivery survives process death and retries on network loss,
        // rather than doing the network inline in the short-lived receiver.
        val request = OneTimeWorkRequestBuilder<RelayWorker>()
            .setInputData(
                workDataOf(
                    RelayWorker.KEY_FROM to sms.from,
                    RelayWorker.KEY_BODY to sms.body,
                    RelayWorker.KEY_SENT to sms.sentMillis,
                    RelayWorker.KEY_RECEIVED to sms.receivedMillis,
                    RelayWorker.KEY_SIM to sms.sim,
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    /**
     * The 0-based SIM slot the SMS arrived on, from the broadcast's slot extra (permission-free; the
     * key varies by OEM). Falls back to slot 0 when absent (single-SIM devices). The user-facing name
     * is then looked up in [SenderSettings], sidestepping the READ_PHONE_STATE requirement for carrier
     * names.
     */
    private fun resolveSlot(intent: Intent): Int {
        for (key in listOf("android.telephony.extra.SLOT_INDEX", "slot", "simSlot", "phone")) {
            val slot = intent.getIntExtra(key, -1)
            if (slot in 0..3) return slot
        }
        return 0
    }
}
