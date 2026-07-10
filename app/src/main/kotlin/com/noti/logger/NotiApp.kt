package com.noti.logger

import android.app.Application
import com.noti.logger.work.UploadScheduler

class NotiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UploadScheduler.applyFromSettings(this)
    }
}
