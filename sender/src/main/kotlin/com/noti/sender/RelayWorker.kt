package com.noti.sender

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noti.sender.sms.CapturedSms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a relay durably: WorkManager persists the job across process death and reboots and retries
 * (with backoff) when [SenderPipeline.relay] reports a transient failure. Enqueued by [SmsReceiver].
 */
class RelayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val from = inputData.getString(KEY_FROM) ?: return Result.success()
        val sms = CapturedSms(
            from = from,
            body = inputData.getString(KEY_BODY).orEmpty(),
            sentMillis = inputData.getLong(KEY_SENT, 0L),
            receivedMillis = inputData.getLong(KEY_RECEIVED, 0L),
            sim = inputData.getString(KEY_SIM).orEmpty(),
        )
        val ok = withContext(Dispatchers.IO) { SenderPipeline.relay(applicationContext, sms) }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_FROM = "from"
        const val KEY_BODY = "body"
        const val KEY_SENT = "sent"
        const val KEY_RECEIVED = "received"
        const val KEY_SIM = "sim"
    }
}
