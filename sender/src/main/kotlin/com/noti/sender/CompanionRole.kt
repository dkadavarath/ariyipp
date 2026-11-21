package com.noti.sender

import android.content.Context
import androidx.work.WorkManager
import com.noti.sender.status.DeviceMonitor

/**
 * Whether this device is currently the companion, as seen from the `:sender` library, which can't
 * read the app's role setting itself. The app installs [check] in `Application.onCreate`, which
 * Android runs before any receiver, service or worker in the process, so every entry point below
 * sees the real role.
 *
 * Everything that relays (the SMS receiver, boot, the scan workers, the keep-alive service and the
 * status watchers) asks [active] first. Without it, switching a phone from Companion to Main left
 * the manifest receivers and the persisted WorkManager jobs running, and the phone went on pushing
 * every SMS to its old Main and old webhook.
 */
object CompanionRole {

    /** Installed by the app. The default keeps the library usable on its own (tests). */
    @Volatile
    var check: (Context) -> Boolean = { true }

    fun active(context: Context): Boolean =
        try { check(context.applicationContext) } catch (e: Exception) { true }

    /** Unique WorkManager names of every companion job, for [stopAll]. */
    internal val WORK_NAMES = listOf(
        "sms-scan", "sms-scan-follow-up", "sms-scan-periodic",
        "status-watch-periodic", "sms-repush", "token-announce",
    )

    /** Stop everything the companion runs in the background: this device is no longer one. Safe to
     *  call repeatedly; the app calls it on every Main process start. */
    fun stopAll(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        WORK_NAMES.forEach { wm.cancelUniqueWork(it) }
        KeepAliveService.stop(app)
        DeviceMonitor.stop(app)
    }
}
