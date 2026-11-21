package com.noti.logger.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.logger.reminder.ReminderScheduler
import com.noti.logger.work.ReminderScanWorker
import com.noti.logger.work.UploadScheduler

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                UploadScheduler.applyFromSettings(context)
                // Alarms do not survive a reboot - every pending reminder has to be re-armed from
                // the database, or a bill scheduled last week silently never fires.
                ReminderScanWorker.applyFromSettings(context)
                val app = context.applicationContext
                Thread { ReminderScheduler.rebuildAll(app) }.apply { name = "reminder-boot" }.start()
            }
        }
    }
}
