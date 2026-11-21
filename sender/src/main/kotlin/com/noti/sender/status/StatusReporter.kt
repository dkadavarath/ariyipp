package com.noti.sender.status

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.Diag
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.FcmSender
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sends device-status snapshots to Main - both the streamed ones inside a subscription window and
 * the one-off ones an alert produces.
 *
 * The subscription deadline is deliberately held in memory only. It must not survive a process
 * restart: a subscription is Main saying "I am looking at the screen right now", which cannot still
 * be true after this app was killed and rebuilt, and Main renews every
 * [DeviceStatusPolicy.RENEW_SECONDS] anyway - so the readout recovers within seconds while a
 * persisted deadline would instead resume streaming at a Main that walked away long ago.
 */
object StatusReporter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var deadlineMs: Long = 0L
    private var loop: Job? = null

    /** Main asked for a window (or, with 0, asked us to stop). */
    @Synchronized
    fun onRequest(context: Context, forSeconds: Int) {
        val app = context.applicationContext
        if (!SenderSettings.get(app).statusSharingEnabled) {
            Diag.log("status request ignored - status sharing is OFF on this device")
            return
        }
        deadlineMs = DeviceStatusPolicy.deadlineFor(forSeconds, System.currentTimeMillis())
        if (deadlineMs == 0L) {
            loop?.cancel()
            loop = null
            Diag.log("status stream stopped (main closed the screen)")
            return
        }
        if (loop?.isActive == true) return // a renewal: the running loop picks up the new deadline
        loop = scope.launch {
            Diag.log("status stream started")
            while (isActive && DeviceStatusPolicy.subscriptionActive(deadlineMs, System.currentTimeMillis())) {
                send(app, WireMessage.StatusReport.POLL)
                delay(SenderSettings.get(app).statusPollSeconds * 1000L)
            }
            Diag.log("status stream ended (window expired)")
        }
    }

    /** True while Main is actively watching, so callers can skip redundant work. */
    fun streaming(): Boolean = DeviceStatusPolicy.subscriptionActive(deadlineMs, System.currentTimeMillis())

    /** Blocking send of one snapshot. Best-effort: a failure is logged, never retried - the next
     *  poll (or, for an alert, the periodic re-evaluation in [StatusWatchWorker]) is the retry. */
    fun send(context: Context, trigger: Int): Boolean {
        val s = SenderSettings.get(context)
        if (!s.statusSharingEnabled) return false
        if (s.serviceAccountJson.isBlank() || s.notiFcmToken.isBlank() || s.relayKey.isBlank()) return false
        return try {
            val report = StatusCollector.snapshot(context, trigger)
            val payload = MessageCrypto.encrypt(Wire.encodeFor(report, com.noti.shared.Wire.Direction.COMPANION_TO_MAIN), s.relayKey)
            val res = FcmSender.forServiceAccount(s.serviceAccountJson).send(s.notiFcmToken, mapOf("payload" to payload), label = com.noti.shared.FcmLabel.STATUS_REPORT)
            if (!res.ok) Diag.log("status → HTTP ${res.httpCode} (${res.detail.take(40)})")
            else if (WireMessage.StatusReport.isAlert(trigger)) Diag.log("status alert sent to main (trigger $trigger)")
            res.ok
        } catch (e: Exception) {
            Diag.log("status → ERROR: ${e.message}")
            false
        }
    }
}
