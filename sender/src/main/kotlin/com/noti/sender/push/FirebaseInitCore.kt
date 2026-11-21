package com.noti.sender.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.noti.shared.GoogleServices

/**
 * The actual "init Firebase from a google-services.json string" logic (BYO-FCM at runtime instead of
 * baked in at build time). Lives here — rather than in `:app` — so this library and its consumer can
 * both use it: `:sender`'s own screens call it directly (a device running this code is always the
 * companion role); `:app`'s role-aware wrapper ([com.noti.logger.push.FirebaseInit], not visible from
 * here) picks which settings store to read before delegating to this.
 */
object FirebaseInitCore {

    /** True when a default FirebaseApp is available — already initialized, or just initialized now. */
    fun initFrom(context: Context, configJson: String): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        if (configJson.isBlank()) return false
        return try {
            val cfg = GoogleServices.parse(configJson, context.packageName)
            val options = FirebaseOptions.Builder()
                .setApiKey(cfg.apiKey)
                .setApplicationId(cfg.applicationId)
                .setProjectId(cfg.projectId)
                .setGcmSenderId(cfg.gcmSenderId)
                .build()
            FirebaseApp.initializeApp(context, options)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Firebase is initialized once per process, so a google-services.json imported after that -
     * for a different project - had no effect until the app happened to restart. True when [configJson]
     * names a different project or app than the one running now, so the caller should restart.
     */
    fun needsRestartFor(context: Context, configJson: String): Boolean = try {
        val running = FirebaseApp.getApps(context).firstOrNull()?.options ?: return false
        val cfg = GoogleServices.parse(configJson, context.packageName)
        running.projectId != cfg.projectId || running.applicationId != cfg.applicationId
    } catch (e: Exception) {
        false
    }

    /** Relaunches the app in a fresh process, so Firebase starts from the newly imported config. */
    fun restartApp(activity: android.app.Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(launch)
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Offers the restart [needsRestartFor] calls for. */
    fun offerRestart(activity: android.app.Activity) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(com.noti.sender.R.string.fb_restart_title)
            .setMessage(com.noti.sender.R.string.fb_restart_msg)
            .setNegativeButton(com.noti.sender.R.string.fb_restart_later, null)
            .setPositiveButton(com.noti.sender.R.string.fb_restart_now) { _, _ -> restartApp(activity) }
            .show()
    }
}
