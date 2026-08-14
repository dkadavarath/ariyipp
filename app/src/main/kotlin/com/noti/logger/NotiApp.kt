package com.noti.logger

import android.app.Application
import com.noti.logger.config.Settings
import com.noti.logger.util.Theming
import com.noti.logger.work.UploadScheduler
import com.noti.sender.SmsSyncWorker
import com.noti.shared.Role

class NotiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the user's light/dark/system choice. Color (Default blue vs Material You) is applied
        // per-activity via Theming.applyDynamicColorIfEnabled().
        Theming.applyNightMode(this)

        when (Settings.get(this).role) {
            Role.COMPANION -> {
                // Keep the relay backstop scheduled and the high-water mark set from any process start.
                SmsSyncWorker.baselineIfNeeded(this)
                SmsSyncWorker.schedulePeriodic(this)
                // Re-announce our endpoint so Main always has a live token (self-heals a stale one,
                // e.g. an in-place hub update that kept the old ariy token). No-op if not paired.
                com.noti.sender.TokenAnnounceWorker.enqueue(this)
            }
            // MAIN (or not-yet-chosen): the hub's notification-upload backstop.
            else -> UploadScheduler.applyFromSettings(this)
        }
    }
}
