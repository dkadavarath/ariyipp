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

    /**
     * Sends [body] to [to]. [sim] is a SIM slot index (0/1); -1 uses the device's default SIM.
     * Selecting a specific slot needs READ_PHONE_STATE to resolve the subscription - without it (or
     * if the slot is empty) we fall back to the default SIM rather than fail. [msgId] (0 if none) is
     * Main's row id for this message; when set, [SmsStatusReceiver] acks its outcome back to Main.
     */
    fun send(context: Context, to: String, body: String, sim: Int = -1, msgId: Long = 0) {
        val sm = smsManagerForSlot(context, sim) ?: defaultSmsManager(context)
        val parts = sm.divideMessage(body)
        val sentIntent = statusPendingIntent(context, ACTION_SMS_SENT, msgId)
        val deliveredIntent = statusPendingIntent(context, ACTION_SMS_DELIVERED, msgId)
        if (parts.size > 1) {
            // Only the last part carries a callback - one ack per logical message, not one per part.
            val sentIntents = ArrayList<PendingIntent?>(parts.size)
            val deliveryIntents = ArrayList<PendingIntent?>(parts.size)
            parts.indices.forEach { i ->
                val isLast = i == parts.size - 1
                sentIntents.add(if (isLast) sentIntent else null)
                deliveryIntents.add(if (isLast) deliveredIntent else null)
            }
            sm.sendMultipartTextMessage(to, null, parts, sentIntents, deliveryIntents)
        } else {
            sm.sendTextMessage(to, null, body, sentIntent, deliveredIntent)
        }
    }

    /** Null when there's no [msgId] to report against (e.g. a manually-typed send with no Main
     *  counterpart), so SmsManager just skips the callback instead of firing on a no-op receiver. */
    private fun statusPendingIntent(context: Context, action: String, msgId: Long): PendingIntent? {
        if (msgId <= 0) return null
        val intent = Intent(action).setPackage(context.packageName).putExtra(EXTRA_MSG_ID, msgId)
        // requestCode must be unique per (msgId, action) pair so concurrent sends' PendingIntents
        // (and their extras) don't collide/overwrite each other.
        val requestCode = (action + msgId).hashCode()
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
