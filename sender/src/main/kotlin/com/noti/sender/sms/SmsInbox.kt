package com.noti.sender.sms

import android.content.Context
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.noti.sender.config.SenderSettings

/** Reads the SMS inbox so the sync can find messages that weren't relayed live (missed in Doze). */
object SmsInbox {

    /** Inbox messages with `date` strictly greater than [sinceDate], oldest first. */
    fun since(context: Context, sinceDate: Long): List<CapturedSms> {
        val out = ArrayList<CapturedSms>()
        val cols = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT, Telephony.Sms.SUBSCRIPTION_ID,
        )
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, cols,
            "${Telephony.Sms.DATE} > ?", arrayOf(sinceDate.toString()),
            "${Telephony.Sms.DATE} ASC",
        ) ?: return out
        cursor.use { c ->
            val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iSent = c.getColumnIndex(Telephony.Sms.DATE_SENT)
            val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            while (c.moveToNext()) {
                val date = if (iDate >= 0) c.getLong(iDate) else 0L
                out.add(
                    CapturedSms(
                        from = c.getString(iAddr).orEmpty(),
                        body = c.getString(iBody).orEmpty(),
                        sentMillis = if (iSent >= 0) c.getLong(iSent) else date,
                        receivedMillis = date,
                        sim = simLabel(context, if (iSub >= 0) c.getInt(iSub) else -1),
                    )
                )
            }
        }
        return out
    }

    /** Newest inbox `date` (0 if empty) — used to set the sync baseline without backfilling history. */
    fun newestDate(context: Context): Long {
        val c = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC LIMIT 1",
        ) ?: return 0L
        return c.use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    private fun simLabel(context: Context, subId: Int): String {
        if (subId < 0) return ""
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return ""
            val slot = sm.getActiveSubscriptionInfo(subId)?.simSlotIndex ?: return ""
            SenderSettings.get(context).simName(slot)
        } catch (e: Exception) {
            ""
        }
    }
}
