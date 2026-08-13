package com.noti.sender

import android.app.Application
import com.noti.sender.config.SenderSettings
import com.noti.sender.util.Theming

class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the user's light/dark/system choice; color + AMOLED are applied per-activity.
        Theming.applyNightMode(SenderSettings.get(this))
        // Keep the relay backstop scheduled from any process start, and set the high-water mark before
        // the first new SMS so it isn't skipped. (The keep-alive foreground service is only started
        // from the foreground / boot, where a background FGS start is allowed.)
        SmsSyncWorker.baselineIfNeeded(this)
        SmsSyncWorker.schedulePeriodic(this)
    }
}
