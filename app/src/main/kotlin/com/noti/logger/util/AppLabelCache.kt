package com.noti.logger.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache

class AppLabelCache(context: Context) {

    private val pm: PackageManager = context.applicationContext.packageManager
    private val cache = LruCache<String, String>(200)

    fun label(packageName: String): String {
        return cache.get(packageName) ?: resolveAndCache(packageName)
    }

    private fun resolveAndCache(packageName: String): String {
        val label = try {
            @Suppress("DEPRECATION")
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
        cache.put(packageName, label)
        return label
    }
}
