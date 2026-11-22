package com.noti.sender.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** Sends an SMS via the device SIM (requires SEND_SMS; not the default SMS app). Long bodies split. */
object SmsSender {

    const val ACTION_SMS_SENT = "com.noti.sender.SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.noti.sender.SMS_DELIVERED"
    const val EXTRA_MSG_ID = "msg_id"
    /** Which part of a long SMS a callback is for, and how many parts there are. */
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"

    /**
     * Sends [body] to [to]. [sim] is a SIM slot index (0/1); -1 uses the device's default SIM.
     * Selecting a specific slot needs READ_PHONE_STATE to resolve the subscription - without it (or
     * if the slot is empty) we fall back to the default SIM rather than fail. [msgId] (0 if none) is
     * Main's row id for this message; when set, [SmsStatusReceiver] acks its outcome back to Main.
     */
    fun send(context: Context, to: String, body: String, sim: Int = -1, msgId: Long = 0) {
        val sm = smsManagerForSlot(context, sim) ?: defaultSmsManager(context)
        val parts = sm.divideMessage(body)
        if (parts.size > 1) {
            if (msgId > 0) SmsPartTally.reset(context, msgId)
            // Every part reports: the parts go out separately, and the last one succeeding says
            // nothing about the others. SmsStatusReceiver acks the message once all of them have
            // (or as soon as one fails).
            val sentIntents = ArrayList<PendingIntent?>(parts.size)
            val deliveryIntents = ArrayList<PendingIntent?>(parts.size)
            parts.indices.forEach { i ->
                sentIntents.add(statusPendingIntent(context, ACTION_SMS_SENT, msgId, i, parts.size))
                deliveryIntents.add(statusPendingIntent(context, ACTION_SMS_DELIVERED, msgId, i, parts.size))
            }
            sm.sendMultipartTextMessage(to, null, parts, sentIntents, deliveryIntents)
        } else {
            sm.sendTextMessage(
                to, null, body,
                statusPendingIntent(context, ACTION_SMS_SENT, msgId),
                statusPendingIntent(context, ACTION_SMS_DELIVERED, msgId),
            )
        }
    }

    /** Null when there's no [msgId] to report against (e.g. a manually-typed send with no Main
     *  counterpart), so SmsManager just skips the callback instead of firing on a no-op receiver. */
    private fun statusPendingIntent(context: Context, action: String, msgId: Long, part: Int = 0, parts: Int = 1): PendingIntent? {
        if (msgId <= 0) return null
        val intent = Intent(action).setPackage(context.packageName).putExtra(EXTRA_MSG_ID, msgId)
            .putExtra(EXTRA_PART, part).putExtra(EXTRA_PARTS, parts)
        // requestCode must be unique per (msgId, action, part) so concurrent sends' PendingIntents
        // (and their extras) don't collide/overwrite each other.
        val requestCode = (action + msgId + (if (parts > 1) ":$part" else "")).hashCode()
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @Suppress("DEPRECATION")
    private fun defaultSmsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    @Suppress("DEPRECATION")
    private fun smsManagerForSlot(context: Context, slot: Int): SmsManager? {
        if (slot < 0) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val subs = context.getSystemService(SubscriptionManager::class.java) ?: return null
            val subId = subs.getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        } catch (e: Exception) {
            null
        }
    }
}
