package com.noti.sender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noti.sender.config.SenderSettings
import com.noti.shared.Diag

/**
 * The other phone is on a version from before direction tags (Wire.Direction), so everything it
 * sends is refused and the pair can't talk until it's updated. Said once - not per push - and said
 * again only after the peer has sent a tagged message in between.
 */
object PeerVersionNotice {

    private const val CHANNEL = "ariy_peer_version"
    private const val NOTIF_ID = 4713

    /** An untagged push arrived. [peerIsMain] picks the wording. */
    fun onUntagged(context: Context, peerIsMain: Boolean) {
        val peer = if (peerIsMain) "main" else "companion"
        Diag.log("push from $peer REFUSED - it's on an older version without direction tags; update it")
        val s = SenderSettings.get(context)
        if (s.peerOutdatedNotified) return
        s.peerOutdatedNotified = true
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Other phone needs updating", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val title = context.getString(if (peerIsMain) R.string.peer_old_main_title else R.string.peer_old_companion_title)
        val text = context.getString(R.string.peer_old_text)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) } catch (e: SecurityException) { }
    }

    /** A tagged push arrived: the peer is current, so a later regression may be reported again. */
    fun onTagged(context: Context) {
        val s = SenderSettings.get(context)
        if (s.peerOutdatedNotified) {
            s.peerOutdatedNotified = false
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        }
    }
}
