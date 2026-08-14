package com.noti.logger.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noti.logger.ui.MainActivity

/**
 * Posts in-app alerts (as local notifications) when uploads are rejected by the webhook.
 * Notifications originate from our own package, which [com.noti.logger.capture.NotiListenerService]
 * ignores, so alerts are never captured/looped back.
 */
object Alerter {

    private const val CHANNEL_ID = "noti_alerts"
    private const val ALERT_UPLOAD_ERROR_ID = 1001
    private const val ALERT_FAILURES_ID = 1003

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Upload alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Warnings when notification uploads are rejected" }
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

    private fun post(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            // No-ops if POST_NOTIFICATIONS is not granted (API 33+); guard against SecurityException.
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    /** 4xx from the webhook: the batch was rejected; the app will keep retrying. */
    fun alertUploadRejected(context: Context, httpCode: Int, recordCount: Int) {
        post(
            context,
            ALERT_UPLOAD_ERROR_ID,
            "noti: upload rejected (HTTP $httpCode)",
            "The webhook rejected $recordCount record(s) with HTTP $httpCode. " +
                "Retrying automatically - check the webhook URL, auth, or payload settings."
        )
    }

    /** The endpoint accepted the request but rejected some records (per-uid failures). */
    fun alertUploadFailures(context: Context, failedCount: Int, messages: List<String>) {
        val detail = messages.firstOrNull()?.let { first ->
            if (messages.size > 1) "$first (+${messages.size - 1} more)" else first
        } ?: "See the endpoint for details."
        post(
            context,
            ALERT_FAILURES_ID,
            "noti: $failedCount record(s) failed to store",
            "$failedCount record(s) were rejected by the endpoint and will be retried. $detail"
        )
    }

}
