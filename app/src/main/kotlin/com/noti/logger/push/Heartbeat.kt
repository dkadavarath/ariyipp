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
import com.noti.shared.Envelope
import com.noti.shared.FcmLabel
import com.noti.shared.FcmSender
import com.noti.shared.HeartbeatPolicy
import com.noti.shared.Role
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * The liveness heartbeat engine, role-aware so one implementation drives both sides. Each side talks
 * to its peer over the same encrypted FCM channel and stores the peer's last-seen time in its own
 * settings store (Main → [Settings], companion → [SenderSettings]) so its Status screen can read it.
 */
object Heartbeat {

    private data class Peer(val serviceAccountJson: String, val token: String, val key: String, val label: String)

    private fun peer(context: Context): Peer? = when (Settings.get(context).role) {
        Role.COMPANION -> SenderSettings.get(context).let {
            if (it.peerPaired()) Peer(it.serviceAccountJson, it.notiFcmToken, it.relayKey, FcmLabel.HEARTBEAT_TO_MAIN) else null
        }
        else -> Settings.get(context).let {
            if (it.peerPaired()) Peer(it.serviceAccountJson, it.sndiFcmToken, it.relayKey, FcmLabel.HEARTBEAT_TO_COMPANION) else null
        }
    }

    fun paired(context: Context): Boolean = peer(context) != null

    /** Whether the heartbeat runs on this device, from the role's own store. */
    fun enabled(context: Context): Boolean = when (Settings.get(context).role) {
        Role.COMPANION -> SenderSettings.get(context).heartbeatEnabled
        else -> Settings.get(context).heartbeatEnabled
    }

    /** Coarse state for the Status screen. */
    enum class Status { OK, DISABLED, PEER_OFF, FAILED, UNPAIRED }

    fun status(context: Context): Status = when {
        !enabled(context) -> Status.DISABLED
        !paired(context) -> Status.UNPAIRED
        peerOff(context) -> Status.PEER_OFF
        HeartbeatPolicy.isStale(lastBeatAtMs(context), System.currentTimeMillis()) -> Status.FAILED
        else -> Status.OK
    }

    fun lastBeatAtMs(context: Context): Long = when (Settings.get(context).role) {
        Role.COMPANION -> SenderSettings.get(context).lastPeerBeatAtMs
        else -> Settings.get(context).lastPeerBeatAtMs
    }

    fun peerOff(context: Context): Boolean = when (Settings.get(context).role) {
        Role.COMPANION -> SenderSettings.get(context).peerHeartbeatOff
        else -> Settings.get(context).peerHeartbeatOff
    }

    private fun setPeerOff(context: Context, value: Boolean) {
        if (peerOff(context) == value) return
        when (Settings.get(context).role) {
            Role.COMPANION -> SenderSettings.get(context).peerHeartbeatOff = value
            else -> Settings.get(context).peerHeartbeatOff = value
        }
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
        enabled(context) && paired(context) && !peerOff(context) &&
            HeartbeatPolicy.isStale(lastBeatAtMs(context), System.currentTimeMillis())

    /** Peer label for user-facing text, from this device's role. */
    fun peerLabel(context: Context): String =
        if (Settings.get(context).role == Role.COMPANION) context.getString(R.string.hb_peer_main)
        else context.getString(R.string.hb_peer_companion)

    /**
     * Send one beat to the peer. [request] = "pong back now" (a force-check). Blocking network.
     * The routine beat goes at normal priority: it is invisible housekeeping that can wait for the
     * peer's next maintenance window, and every silent high-priority push counts against how FCM
     * treats the app's high-priority pushes. Force-checks and their answers stay high priority.
     */
    fun send(context: Context, request: Boolean, highPriority: Boolean = request, off: Boolean = false): Boolean {
        // A phone with its heartbeat off still says so ([off]), and nothing else.
        if (!enabled(context) && !off) return false
        val p = peer(context) ?: return false
        return try {
            val payload = Envelope.seal(WireMessage.Heartbeat(request && !off, off), if (Settings.get(context).role == Role.COMPANION) Wire.Direction.COMPANION_TO_MAIN else Wire.Direction.MAIN_TO_COMPANION, p.key)
            val res = FcmSender.forServiceAccount(p.serviceAccountJson)
                .send(
                    p.token, mapOf("payload" to payload), label = p.label, highPriority = highPriority,
                    collapseKey = HeartbeatPolicy.COLLAPSE_KEY, ttlSeconds = HeartbeatPolicy.TTL_SECONDS,
                )
            // On success, the message id is what finds this beat in the BigQuery delivery export -
            // "accepted here, never delivered there" is otherwise invisible from either phone.
            val kind = if (off) " (off)" else if (request) " force-check" else if (highPriority) " answer" else ""
            if (res.ok) Diag.log("heartbeat$kind → sent to ${peerLabel(context)} (id ${FcmLabel.messageId(res.detail)})")
            else Diag.log("heartbeat$kind → HTTP ${res.httpCode} (${res.detail.take(40)})")
            res.ok
        } catch (e: Exception) {
            Diag.log("heartbeat → ERROR: ${e.message}"); false
        }
    }

    /**
     * A beat arrived from the peer: record it, clear the warning, and if it was a force-check answer
     * straight away. Runs on FCM's delivery thread, inside the short window a high-priority push
     * grants even under Doze - a WorkManager hop here could be deferred past the asker's wait, which
     * is exactly the false alarm the force-check exists to prevent. The worker is only the fallback.
     */
    fun onBeatReceived(context: Context, request: Boolean, off: Boolean, sentAt: Long) {
        if (!takeStamp(context, sentAt)) {
            // Unstamped (an older peer), or older than something already heard from the peer: a
            // replayed copy. Counting it would hide a peer that's really gone ("off" most of all).
            if (sentAt > 0) Diag.log("heartbeat from ${peerLabel(context)} not counted - older than its last message")
            // A force-check is still answered: it may just have been overtaken by a newer push,
            // and the peer is waiting on the answer. A replayed one costs a single push.
            if (sentAt > 0 && request) {
                val app = context.applicationContext
                Thread { send(app, request = false, highPriority = true, off = !enabled(app)) }.start()
            }
            return
        }
        if (off) {
            // The peer is alive and has its heartbeat off: stop expecting beats from it.
            setPeerOff(context, true)
            setLastBeatAtMs(context, System.currentTimeMillis())
            clearNotification(context)
            Diag.log("heartbeat: ${peerLabel(context)} has its heartbeat off - not expecting beats")
            return
        }
        setPeerOff(context, false)
        if (!enabled(context)) {
            clearNotification(context)
            // Ours is off, but the peer is waiting on an answer: tell it, or it reports us as not
            // responding. One small push, only when asked.
            if (request) Thread { send(context, request = false, highPriority = true, off = true) }.start()
            return
        }
        setLastBeatAtMs(context, System.currentTimeMillis())
        clearNotification(context)
        Diag.log("heartbeat received from ${peerLabel(context)}${if (request) " (force-check)" else ""}")
        if (request && !send(context, request = false, highPriority = true)) HeartbeatWorker.answerNow(context)
    }

    /** This phone's heartbeat was just switched off: tell the peer once, off the caller's thread. */
    fun announceOff(context: Context) {
        val app = context.applicationContext
        Thread { send(app, request = false, highPriority = false, off = true) }.apply { name = "heartbeat-off" }.start()
    }

    /**
     * Whether a message from the peer stamped [sentAt] (Wire.sentAt) is newer than everything taken
     * from it so far, and if so, take it as the newest. Only such a message says anything about the
     * peer being alive now: a replayed copy decrypts just as well. An unstamped one is from a
     * version before stamps, which gets the "update the other phone" notice.
     */
    @Synchronized
    fun takeStamp(context: Context, sentAt: Long): Boolean {
        val companion = Settings.get(context).role == Role.COMPANION
        if (sentAt <= 0) {
            com.noti.sender.PeerVersionNotice.onUnstamped(context, peerIsMain = companion)
            return false
        }
        com.noti.sender.PeerVersionNotice.onStamped(context)
        val now = System.currentTimeMillis()
        val mark = if (companion) SenderSettings.get(context).peerStampMark else Settings.get(context).peerStampMark
        if (!com.noti.shared.ReplayGuard.isNew(sentAt, mark, now)) return false
        val next = com.noti.shared.ReplayGuard.next(mark, sentAt, now)
        if (companion) SenderSettings.get(context).peerStampMark = next else Settings.get(context).peerStampMark = next
        return true
    }

    /**
     * Any other authenticated push from the peer (a relayed SMS, an ack, a status report, a command)
     * proves it is alive just as well as a beat does - if it's new (see [takeStamp]). Before this
     * only beats counted, so an active companion could be streaming status to the open Diagnostics
     * screen while being reported as not responding. Throttled; see
     * [HeartbeatPolicy.ALIVE_WRITE_THROTTLE_MS].
     */
    fun notePeerAlive(context: Context, sentAt: Long) {
        if (!takeStamp(context, sentAt)) return
        if (!enabled(context) || !paired(context)) return
        val now = System.currentTimeMillis()
        if (!HeartbeatPolicy.shouldRecordAlive(lastBeatAtMs(context), now)) return
        setLastBeatAtMs(context, now)
        clearNotification(context)
    }

    /** Post the disconnected warning if stale, otherwise clear it. Only called once a force-check
     *  has gone unanswered (or could not be sent) - never on staleness alone. */
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
