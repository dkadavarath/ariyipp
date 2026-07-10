package com.noti.logger.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A launchable app shown in the picker. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

object InstalledApps {

    /**
     * Launchable apps (those with a MAIN/LAUNCHER activity), deduped by package, our own package
     * removed, sorted by label. Visibility comes from the manifest <queries> entry — no
     * QUERY_ALL_PACKAGES needed. Runs off the main thread.
     */
    suspend fun loadLaunchableApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.applicationContext.packageManager
        val self = context.applicationContext.packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)

        val byPackage = LinkedHashMap<String, AppInfo>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == self || byPackage.containsKey(pkg)) continue
            val label = ri.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg }
            val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
            byPackage[pkg] = AppInfo(pkg, label, icon)
        }
        byPackage.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /**
     * Pure filter used by the picker's search box (also unit-testable): case-insensitive match on
     * label or package name.
     */
    fun filter(apps: List<AppInfo>, query: String): List<AppInfo> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }
}
