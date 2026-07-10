package com.noti.logger.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.logger.work.UploadScheduler

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                UploadScheduler.applyFromSettings(context)
            }
        }
    }
}
