package com.noti.logger.push

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity

/**
 * Handles the message notification's action buttons: an inline Reply (sends the typed text back to
 * the sender via ariy, the same path as composing in a chat) and Mark as read. Work runs off the
 * main thread via [goAsync]; the notification is dismissed/updated once it completes so the inline
 * reply's progress spinner clears.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        // Copy an OTP to the clipboard — no sender needed, and quick enough for the main thread.
        if (intent.action == ACTION_COPY_CODE) {
            val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
            if (code.isNotBlank()) copyCode(app, code)
            return
        }

        val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (sender.isBlank()) return

        when (intent.action) {
            ACTION_MARK_READ -> {
                val pending = goAsync()
                Thread {
                    try {
                        NotiDatabase.get(app).relayedMessageDao().markRead(sender)
                        NotificationManagerCompat.from(app).cancel(notifId)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }

            ACTION_MUTE -> {
                // Mute only this conversation: future messages still arrive as unread, just silently.
                val pending = goAsync()
                Thread {
                    try {
                        Settings.get(app).setMuted(sender, true)
                        NotificationManagerCompat.from(app).cancel(notifId)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }

            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
                val pending = goAsync()
                Thread {
                    try {
                        if (text.isEmpty()) {
                            NotificationManagerCompat.from(app).cancel(notifId)
                            return@Thread
                        }
                        val dao = NotiDatabase.get(app).relayedMessageDao()
                        // Optimistically record the outgoing message (same as in-chat compose).
                        dao.insert(
                            RelayedMessageEntity(
                                sender = sender, sim = "", body = text,
                                receivedAt = System.currentTimeMillis(), outgoing = 1,
                            )
                        )
                        dao.markRead(sender)
                        val ok = NotiCommandSender.send(app, sender, text)
                        if (ok) {
                            NotificationManagerCompat.from(app).cancel(notifId)
                        } else {
                            // Clear the spinner and tell the user it didn't go through.
                            MessageNotifier.showReplyFailed(app, sender, notifId, text)
                        }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun copyCode(context: Context, code: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(context.getString(R.string.notif_copy_label), code)
        // Mark the clip sensitive so the OS doesn't preview the code (Android 13+).
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        // Android 13+ shows its own "copied" confirmation; older versions need a toast.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, R.string.notif_code_copied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_REPLY = "com.noti.logger.action.REPLY"
        const val ACTION_MARK_READ = "com.noti.logger.action.MARK_READ"
        const val ACTION_MUTE = "com.noti.logger.action.MUTE"
        const val ACTION_COPY_CODE = "com.noti.logger.action.COPY_CODE"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_CODE = "code"
        const val KEY_REPLY = "reply_text"
    }
}
