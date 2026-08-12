package com.noti.logger.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.ui.ChatActivity
import com.noti.logger.ui.MainActivity
import com.noti.logger.util.OtpExtractor
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

    /**
     * Posts [title]/[body] as a heads-up notification; returns the notification id used. When
     * [sender] and [messageId] are supplied, tapping the notification opens that exact chat with the
     * message highlighted; otherwise it just opens the app.
     */
    fun show(
        context: Context,
        title: String,
        body: String,
        sender: String = "",
        messageId: Long = -1L,
    ): Int {
        ensureChannel(context)
        val settings = Settings.get(context)
        val id = nextId.getAndIncrement()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(context, sender, messageId, id))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        // Optionally stop the OS adding its own contextual actions (e.g. its OTP-copy chip).
        if (settings.suppressSystemNotifActions) {
            builder.setAllowSystemGeneratedContextualActions(false)
        }
        // Offer Copy code for an OTP (if enabled), or Reply otherwise — not both — plus Mark as read.
        val code = OtpExtractor.extract(body)
        when {
            code != null && settings.otpCopyEnabled -> builder.addAction(copyCodeAction(context, code, id))
            code == null && sender.isNotBlank() -> builder.addAction(replyAction(context, sender, id))
        }
        if (sender.isNotBlank()) {
            builder.addAction(markReadAction(context, sender, id))
        }
        val notification = builder.build()
        try {
            // No-ops if POST_NOTIFICATIONS is not granted (API 33+); guard against SecurityException.
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
        return id
    }

    /** Re-posts the notification to clear the inline-reply spinner when a reply couldn't be sent. */
    fun showReplyFailed(context: Context, sender: String, id: Int, replyText: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notif_reply_failed_title, sender))
            .setContentText(context.getString(R.string.notif_reply_failed_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_reply_failed_body)))
            .setContentIntent(contentIntent(context, sender, -1L, id))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun replyAction(context: Context, sender: String, id: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.notif_action_reply))
            .build()
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_REPLY)
            .putExtra(NotificationActionReceiver.EXTRA_SENDER, sender)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, id)
        // RemoteInput requires a mutable PendingIntent so the system can attach the typed text.
        val pending = PendingIntent.getBroadcast(
            context, id + 1_000_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_edit, context.getString(R.string.notif_action_reply), pending,
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()
    }

    private fun copyCodeAction(context: Context, code: String, id: Int): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_COPY_CODE)
            .putExtra(NotificationActionReceiver.EXTRA_CODE, code)
        val pending = PendingIntent.getBroadcast(
            context, id + 3_000_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_edit, context.getString(R.string.notif_action_copy_code, code), pending,
        ).build()
    }

    private fun markReadAction(context: Context, sender: String, id: Int): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_MARK_READ)
            .putExtra(NotificationActionReceiver.EXTRA_SENDER, sender)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, id)
        val pending = PendingIntent.getBroadcast(
            context, id + 2_000_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_status_active, context.getString(R.string.notif_action_mark_read), pending,
        ).build()
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

    private fun contentIntent(
        context: Context,
        sender: String,
        messageId: Long,
        requestCode: Int,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Deep-link into the exact chat, with the Messages list beneath it, so Back from the chat lands
        // on the conversation list (not the Status home tab). Falls back to just opening the app.
        if (sender.isNotBlank()) {
            val list = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_MESSAGES)
            val chat = Intent(context, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_SENDER, sender)
                .putExtra(ChatActivity.EXTRA_HIGHLIGHT_ID, messageId)
            return TaskStackBuilder.create(context)
                .addNextIntent(list)
                .addNextIntent(chat)
                .getPendingIntent(requestCode, flags)!!
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }
}
