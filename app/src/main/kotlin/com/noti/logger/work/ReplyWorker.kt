package com.noti.logger.work

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.noti.logger.push.MessageNotifier
import com.noti.logger.push.NotiCommandSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends an inline reply typed into a message notification. The send is a full FCM call (plus an
 * OAuth refresh when the token has expired), which can outlast the ~10 s a broadcast receiver gets
 * even with goAsync - when it did, the process was killed mid-send and the reply spinner hung. The
 * receiver now only records the message and hands the send to this job.
 */
class ReplyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val text = inputData.getString(KEY_TEXT).orEmpty()
        val msgId = inputData.getLong(KEY_MSG, 0L)
        val notifId = inputData.getInt(KEY_NOTIF, -1)
        if (sender.isBlank() || text.isBlank()) return@withContext Result.success()
        if (NotiCommandSender.send(applicationContext, sender, text, msgId = msgId)) {
            NotificationManagerCompat.from(applicationContext).cancel(notifId)
        } else {
            // Clear the spinner and say it didn't go through; the message stays in the chat.
            MessageNotifier.showReplyFailed(applicationContext, sender, notifId, text)
        }
        Result.success()
    }

    companion object {
        private const val KEY_SENDER = "sender"
        private const val KEY_TEXT = "text"
        private const val KEY_MSG = "msg"
        private const val KEY_NOTIF = "notif"

        fun enqueue(context: Context, sender: String, text: String, msgId: Long, notifId: Int) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ReplyWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(KEY_SENDER to sender, KEY_TEXT to text, KEY_MSG to msgId, KEY_NOTIF to notifId))
                    .build()
            )
        }
    }
}
