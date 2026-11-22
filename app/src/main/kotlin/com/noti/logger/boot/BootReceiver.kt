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
            // Bill reminders fire at a local time of day ("09:00"), armed as an absolute instant: a
            // new time zone (travel) or a changed clock leaves them at the old zone's 09:00 until
            // the next rescan. Re-arm now.
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                val app = context.applicationContext
                val pending = goAsync()
                Thread {
                    try { ReminderScheduler.rebuildAll(app) } finally { pending.finish() }
                }.apply { name = "reminder-clock" }.start()
            }
            // After an update: nothing to do here. Receiving it is the point: it starts the process,
            // and NotiApp's start-up re-arms alarms and workers the update cleared.
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
        }
    }
}
