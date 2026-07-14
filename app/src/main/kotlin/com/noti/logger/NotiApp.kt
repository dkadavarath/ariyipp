package com.noti.logger

import android.app.Application
import com.noti.logger.work.UploadScheduler

class NotiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Uses the fixed brand-blue theme (matches the icon) for consistent contrast; still
        // follows the system light/dark setting via the DayNight theme. To adopt Material You
        // dynamic color on Android 12+ instead, call DynamicColors.applyToActivitiesIfAvailable(this).
        UploadScheduler.applyFromSettings(this)
    }
}
