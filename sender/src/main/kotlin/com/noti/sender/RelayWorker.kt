package com.noti.sender

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noti.shared.RelayMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a relay durably: WorkManager persists the job across process death and reboots and retries
 * (with backoff) when [SenderPipeline.relay] reports a transient failure. Enqueued by [SmsReceiver].
 */
class RelayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val ok = withContext(Dispatchers.IO) {
            SenderPipeline.relay(applicationContext, RelayMessage(title, body))
        }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
    }
}
