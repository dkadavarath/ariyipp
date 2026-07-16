package com.noti.logger.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A launchable app shown in the picker. */
data class AppInfo(
    val packageName: String,
    val label: String,
    /** Launcher component; the icon is loaded from it lazily, when a row binds. */
    val component: ComponentName? = null
)

object InstalledApps {

    /**
     * Launchable apps (those with a MAIN/LAUNCHER activity), deduped by package, our own package
     * removed, sorted by label. Visibility comes from the manifest <queries> entry — no
     * QUERY_ALL_PACKAGES needed. Runs off the main thread.
     *
     * Deliberately does NOT load icons: decoding one per installed app dominates the cost of this
     * call and stalls the screen. [AppIconLoader] fetches them per row instead, as rows bind.
     */
    suspend fun loadLaunchableApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.applicationContext.packageManager
        val self = context.applicationContext.packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)

        val byPackage = LinkedHashMap<String, AppInfo>()
        for (ri in resolved) {
            val activity = ri.activityInfo ?: continue
            val pkg = activity.packageName ?: continue
            if (pkg == self || byPackage.containsKey(pkg)) continue
            val label = ri.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg }
            byPackage[pkg] = AppInfo(pkg, label, ComponentName(pkg, activity.name))
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
