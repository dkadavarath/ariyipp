package com.noti.logger

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.noti.logger.work.UploadScheduler

class NotiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: apply the device's dynamic color scheme on Android 12+ (no-op below).
        DynamicColors.applyToActivitiesIfAvailable(this)
        UploadScheduler.applyFromSettings(this)
    }
}
