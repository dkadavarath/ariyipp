package com.noti.sender.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** Sends an SMS via the device SIM (requires SEND_SMS; not the default SMS app). Long bodies split. */
object SmsSender {

    /**
     * Sends [body] to [to]. [sim] is a SIM slot index (0/1); -1 uses the device's default SIM.
     * Selecting a specific slot needs READ_PHONE_STATE to resolve the subscription - without it (or
     * if the slot is empty) we fall back to the default SIM rather than fail.
     */
    fun send(context: Context, to: String, body: String, sim: Int = -1) {
        val sm = smsManagerForSlot(context, sim) ?: defaultSmsManager(context)
        val parts = sm.divideMessage(body)
        if (parts.size > 1) {
            sm.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            sm.sendTextMessage(to, null, body, null, null)
        }
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
