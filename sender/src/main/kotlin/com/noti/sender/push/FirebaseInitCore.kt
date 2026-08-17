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
}
