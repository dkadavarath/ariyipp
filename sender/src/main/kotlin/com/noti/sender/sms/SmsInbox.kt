package com.noti.sender.sms

import android.content.Context
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.noti.sender.config.SenderSettings

/**
 * Reads the SMS inbox from the content provider - the single source of truth. Relaying keys off the
 * provider's stable, monotonic row [Telephony.Sms._ID], so a duplicate SMS_RECEIVED broadcast (a
 * known Samsung / dual-SIM quirk) can't cause a double-relay: the provider still has one row.
 */
object SmsInbox {

    private val COLUMNS = arrayOf(
        Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
        Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.SUBSCRIPTION_ID,
    )

    /** Inbox messages with `_id` strictly greater than [sinceId], oldest first. */
    fun since(context: Context, sinceId: Long): List<CapturedSms> {
        val out = ArrayList<CapturedSms>()
        // SIM labels resolved once per subscription, not per row: simLabel() ends in an
        // EncryptedSharedPreferences read (simName), which would otherwise decrypt 1-2 times
        // per scanned SMS during a burst inbox scan.
        val simLabels = HashMap<Int, String>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, COLUMNS,
            "${Telephony.Sms._ID} > ?", arrayOf(sinceId.toString()),
            "${Telephony.Sms._ID} ASC",
        ) ?: return out
        cursor.use { c ->
            val iId = c.getColumnIndex(Telephony.Sms._ID)
            val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iSent = c.getColumnIndex(Telephony.Sms.DATE_SENT)
            val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            while (c.moveToNext()) {
                val date = if (iDate >= 0) c.getLong(iDate) else 0L
                val subId = if (iSub >= 0) c.getInt(iSub) else -1
                out.add(
                    CapturedSms(
                        from = c.getString(iAddr).orEmpty(),
                        body = c.getString(iBody).orEmpty(),
                        sentMillis = if (iSent >= 0) c.getLong(iSent) else date,
                        receivedMillis = date,
                        sim = simLabels.getOrPut(subId) { simLabel(context, subId) },
                        id = if (iId >= 0) c.getLong(iId) else -1,
                    )
                )
            }
        }
        return out
    }

    /** Newest inbox `_id` (0 if empty) - used to baseline the relay marks without backfilling. */
    fun newestId(context: Context): Long {
        val c = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            null, null, "${Telephony.Sms._ID} DESC LIMIT 1",
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
