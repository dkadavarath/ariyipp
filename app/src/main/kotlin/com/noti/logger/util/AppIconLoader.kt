package com.noti.logger.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads app icons one at a time, off the main thread, and memoizes them.
 *
 * Icons are the expensive part of listing installed apps, so they are fetched as rows bind rather
 * than up front. [cached] lets a caller paint an already-loaded icon synchronously, which keeps
 * scrolling free of flicker on rows that have been seen before.
 */
class AppIconLoader(context: Context) {

    private val pm: PackageManager = context.applicationContext.packageManager
    private val cache = LruCache<String, Drawable>(200)

    /** Already-resolved icon, or null if it has not been loaded yet. Safe on the main thread. */
    fun cached(packageName: String): Drawable? = cache.get(packageName)

    /** Resolves the icon (from cache when possible). Falls back to the package's app icon. */
    suspend fun load(app: AppInfo): Drawable? {
        cache.get(app.packageName)?.let { return it }
        val icon = withContext(Dispatchers.IO) {
            try {
                app.component?.let { pm.getActivityIcon(it) } ?: pm.getApplicationIcon(app.packageName)
            } catch (e: Exception) {
                null
            }
        }
        return icon?.also { cache.put(app.packageName, it) }
    }
}
