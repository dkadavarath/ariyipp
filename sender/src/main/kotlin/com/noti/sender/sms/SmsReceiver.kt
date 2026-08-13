package com.noti.sender.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.noti.sender.SmsSyncWorker
import com.noti.shared.Diag

/**
 * Wakes on an incoming SMS (via RECEIVE_SMS; not the default SMS app) and nudges a relay scan. It
 * deliberately does not read the broadcast payload — the relay works off the SMS provider's stable
 * row id instead, so a duplicate broadcast (some OEMs / dual-SIM fire it more than once) can't cause
 * a double-relay. The scan (in [SmsSyncWorker]) reads the provider and relays anything new.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Diag.log("SMS received — scanning inbox to relay")
        SmsSyncWorker.scanSoon(context.applicationContext)
    }
}
