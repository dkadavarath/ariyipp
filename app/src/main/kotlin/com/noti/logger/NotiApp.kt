package com.noti.logger

import android.app.Application
import com.noti.logger.config.Settings
import com.noti.logger.util.Theming
import com.noti.logger.work.UploadScheduler

class NotiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the user's light/dark/system choice. Color (Default blue vs Material You) is applied
        // per-activity via Theming.applyDynamicColorIfEnabled().
        Theming.applyNightMode(Settings.get(this))
        UploadScheduler.applyFromSettings(this)
    }
}
