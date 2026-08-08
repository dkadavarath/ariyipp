package com.noti.sender.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/** Sends an SMS via the device SIM (requires SEND_SMS; not the default SMS app). Long bodies split. */
object SmsSender {

    fun send(context: Context, to: String, body: String) {
        @Suppress("DEPRECATION")
        val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        val parts = sm.divideMessage(body)
        if (parts.size > 1) {
            sm.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            sm.sendTextMessage(to, null, body, null, null)
        }
    }
}
