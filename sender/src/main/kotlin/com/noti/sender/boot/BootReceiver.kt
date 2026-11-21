package com.noti.sender.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.sender.KeepAliveService
import com.noti.sender.SmsSyncWorker
import com.noti.sender.StatusWatchWorker

/** After a reboot, bring the relay back: keep-alive service, periodic sync, a catch-up sync, and
 *  the device-status backstop. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!com.noti.sender.CompanionRole.active(context)) return
        KeepAliveService.ensureRunning(context)
        SmsSyncWorker.schedulePeriodic(context)
        SmsSyncWorker.scanNow(context)
        StatusWatchWorker.schedule(context)
    }
}
