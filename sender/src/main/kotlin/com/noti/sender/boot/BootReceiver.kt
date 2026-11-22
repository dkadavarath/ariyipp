package com.noti.sender.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.sender.KeepAliveService
import com.noti.sender.SmsSyncWorker
import com.noti.sender.StatusWatchWorker

/** After a reboot or an app update, bring the relay back: keep-alive service, periodic sync, a
 *  catch-up sync, and the device-status backstop. An update kills the process and its service, and
 *  without this the relay stayed down until the app was next opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!com.noti.sender.CompanionRole.active(context)) return
        KeepAliveService.ensureRunning(context)
        SmsSyncWorker.schedulePeriodic(context)
        SmsSyncWorker.scanNow(context)
        StatusWatchWorker.schedule(context)
    }
}
