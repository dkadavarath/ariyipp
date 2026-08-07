package com.noti.logger.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noti.logger.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Displays a relayed message (e.g. an SMS/OTP pushed from the sender app via FCM, decrypted on
 * arrival) as a local notification. Like [com.noti.logger.alert.Alerter], these originate from our
 * own package, so [com.noti.logger.capture.NotiListenerService] ignores them — no capture loop.
 */
object MessageNotifier {

    private const val CHANNEL_ID = "noti_relayed"

    // Distinct from Alerter's 100x ids, and unique per message so notifications stack rather than
    // replace one another. Resets per process, which is fine — already-shown ones keep their ids.
    private val nextId = AtomicInteger(2000)

    /** Posts [title]/[body] as a heads-up notification; returns the notification id used. */
    fun show(context: Context, title: String, body: String): Int {
        ensureChannel(context)
        val id = nextId.getAndIncrement()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(context))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            // No-ops if POST_NOTIFICATIONS is not granted (API 33+); guard against SecurityException.
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
        return id
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Relayed messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Messages pushed to this device (e.g. a forwarded SMS)" }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
