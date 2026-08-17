package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.sender.config.SenderSettings
import com.noti.sender.push.FirebaseInitCore
import com.noti.shared.Role

/**
 * Role-aware wrapper around [FirebaseInitCore]: picks which settings store holds the imported
 * `google-services.json` (Main → [Settings], companion → [SenderSettings]) based on this device's
 * role, then delegates. Idempotent and safe to call on every process start and before every
 * FirebaseMessaging use; a no-op once already initialized.
 */
object FirebaseInit {

    fun ensureInitialized(context: Context): Boolean {
        val configJson = when (Settings.get(context).role) {
            Role.COMPANION -> SenderSettings.get(context).firebaseConfigJson
            else -> Settings.get(context).firebaseConfigJson
        }
        return FirebaseInitCore.initFrom(context, configJson)
    }
}
