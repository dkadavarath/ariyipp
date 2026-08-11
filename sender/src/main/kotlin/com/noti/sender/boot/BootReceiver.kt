package com.noti.sender.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.sender.KeepAliveService
import com.noti.sender.SmsSyncWorker

/** After a reboot, bring the relay back: keep-alive service, periodic sync, and a catch-up sync. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        KeepAliveService.ensureRunning(context)
        SmsSyncWorker.schedulePeriodic(context)
        SmsSyncWorker.syncNow(context)
    }
}
