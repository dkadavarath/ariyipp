package com.noti.logger.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.logger.config.Settings
import com.noti.logger.work.HeartbeatWorker
import com.noti.logger.work.HubTokenAnnounceWorker
import com.noti.shared.Role

/**
 * Handles the "Retry now" action from the disconnected notification and the Status-screen banner
 * (the companion's banner lives in the library and reaches this via an explicit broadcast). Kicks a
 * one-shot force-check at the peer. On Main it also re-sends the hub's current token first: if that
 * rotated, the companion's pong would otherwise go to the dead endpoint and the retry could never pass.
 */
class HeartbeatRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val s = Settings.get(app)
        if (s.role != Role.COMPANION && s.peerPaired() && FirebaseInit.ensureInitialized(app)) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token.isNotBlank()) s.fcmToken = token
                HubTokenAnnounceWorker.enqueue(app)
            }
        }
        HeartbeatWorker.retryNow(app)
    }
}
