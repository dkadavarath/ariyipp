package com.noti.sender

import android.app.Application
import com.noti.sender.config.SenderSettings
import com.noti.sender.util.Theming

class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the user's light/dark/system choice; color + AMOLED are applied per-activity.
        Theming.applyNightMode(SenderSettings.get(this))
    }
}
