package com.noti.logger.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.logger.work.HeartbeatWorker

/**
 * Handles the "Retry now" action from the disconnected notification and the Status-screen banner
 * (the companion's banner lives in the library and reaches this via an explicit broadcast). Kicks a
 * one-shot force-check at the peer.
 */
class HeartbeatRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HeartbeatWorker.retryNow(context.applicationContext)
    }
}
