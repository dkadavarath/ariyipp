package com.noti.logger.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.ui.MainActivity
import com.noti.logger.work.HeartbeatWorker
import com.noti.sender.config.SenderSettings
import com.noti.shared.Diag
import com.noti.shared.FcmSender
import com.noti.shared.HeartbeatPolicy
import com.noti.shared.MessageCrypto
import com.noti.shared.Role
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * The liveness heartbeat engine, role-aware so one implementation drives both sides. Each side talks
 * to its peer over the same encrypted FCM channel and stores the peer's last-seen time in its own
 * settings store (Main → [Settings], companion → [SenderSettings]) so its Status screen can read it.
 */
object Heartbeat {

    private data class Peer(val serviceAccountJson: String, val token: String, val key: String)

    private fun peer(context: Context): Peer? = when (Settings.get(context).role) {
        Role.COMPANION -> SenderSettings.get(context).let {
            if (it.peerPaired()) Peer(it.serviceAccountJson, it.notiFcmToken, it.relayKey) else null
        }
        else -> Settings.get(context).let {
            if (it.peerPaired()) Peer(it.serviceAccountJson, it.sndiFcmToken, it.relayKey) else null
        }
    }

    fun paired(context: Context): Boolean = peer(context) != null

    fun lastBeatAtMs(context: Context): Long = when (Settings.get(context).role) {
        Role.COMPANION -> SenderSettings.get(context).lastPeerBeatAtMs
        else -> Settings.get(context).lastPeerBeatAtMs
    }

    private fun setLastBeatAtMs(context: Context, value: Long) = when (Settings.get(context).role) {
        Role.COMPANION -> SenderSettings.get(context).lastPeerBeatAtMs = value
        else -> Settings.get(context).lastPeerBeatAtMs = value
    }

    /** Start the staleness clock once paired, so a fresh pair doesn't false-alarm before the first beat. */
    fun baselineIfNeeded(context: Context) {
        if (paired(context) && lastBeatAtMs(context) == 0L) setLastBeatAtMs(context, System.currentTimeMillis())
    }

    fun isDisconnected(context: Context): Boolean =
        paired(context) && HeartbeatPolicy.isStale(lastBeatAtMs(context), System.currentTimeMillis())

    /** Peer label for user-facing text, from this device's role. */
    fun peerLabel(context: Context): String =
        if (Settings.get(context).role == Role.COMPANION) context.getString(R.string.hb_peer_main)
        else context.getString(R.string.hb_peer_companion)

    /** Send one beat to the peer. [request] = "pong back now" (used by force-retry). Blocking network. */
    fun send(context: Context, request: Boolean): Boolean {
        val p = peer(context) ?: return false
        return try {
            val payload = MessageCrypto.encrypt(Wire.encode(WireMessage.Heartbeat(request)), p.key)
            val res = FcmSender(p.serviceAccountJson).send(p.token, mapOf("payload" to payload))
            if (!res.ok) Diag.log("heartbeat → HTTP ${res.httpCode} (${res.detail.take(40)})")
            res.ok
        } catch (e: Exception) {
            Diag.log("heartbeat → ERROR: ${e.message}"); false
        }
    }

    /** A beat arrived from the peer: record it, clear the warning, and pong if it asked us to. */
    fun onBeatReceived(context: Context, request: Boolean) {
        setLastBeatAtMs(context, System.currentTimeMillis())
        clearNotification(context)
        Diag.log("heartbeat received from ${peerLabel(context)}${if (request) " (force-check)" else ""}")
        if (request) HeartbeatWorker.answerNow(context)
    }

    /** Post the disconnected warning if stale, otherwise clear it. Called by the periodic worker. */
    fun refreshNotification(context: Context) {
        if (isDisconnected(context)) showDisconnected(context) else clearNotification(context)
    }

    // ---- Notification ----

    private const val CHANNEL_ID = "noti_heartbeat"

    private fun showDisconnected(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.hb_channel), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.hb_channel_desc) }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val retry = PendingIntent.getBroadcast(
            context, 1, Intent(HeartbeatPolicy.ACTION_RETRY).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val peer = peerLabel(context)
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.hb_disconnected_title, peer))
            .setContentText(context.getString(R.string.hb_disconnected_body, HeartbeatPolicy.retryEtaMinutes))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.hb_retry_now), retry)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(HeartbeatPolicy.NOTIFICATION_ID, note)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - the Status banner still surfaces it.
        }
    }

    private fun clearNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(HeartbeatPolicy.NOTIFICATION_ID)
    }
}
