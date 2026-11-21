package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.shared.Diag
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.FcmSender
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Main → Companion for device status: opens/renews/closes the readout stream, and pushes the alert
 * thresholds. Blocking network - call off the main thread.
 */
object NotiStatusSender {

    /** Ask the companion to stream snapshots for [forSeconds] (0 = stop now). */
    fun requestStream(context: Context, forSeconds: Int): Boolean =
        push(context, WireMessage.StatusRequest(forSeconds), "status request")

    /** Push the thresholds. [configVersion] is the clock reading that orders it against earlier
     *  pushes, which is what lets the companion reject a replayed older one. */
    fun pushConfig(context: Context): Boolean {
        val s = Settings.get(context)
        return push(
            context,
            WireMessage.StatusConfig(
                alertsEnabled = s.companionAlertsEnabled,
                batteryPct = DeviceStatusPolicy.clampBatteryPct(s.companionBatteryPct),
                wifiLossMinutes = DeviceStatusPolicy.clampWifiLossMinutes(s.companionWifiLossMinutes),
                pollSeconds = DeviceStatusPolicy.clampPollSeconds(s.companionPollSeconds),
                configVersion = System.currentTimeMillis(),
            ),
            "status config",
        )
    }

    private fun push(context: Context, message: WireMessage, what: String): Boolean {
        val s = Settings.get(context)
        if (!s.companionStatusEnabled || !s.peerPaired()) return false
        return try {
            val payload = MessageCrypto.encrypt(Wire.encode(message), s.relayKey)
            val res = FcmSender.forServiceAccount(s.serviceAccountJson).send(
                s.sndiFcmToken, mapOf("payload" to payload),
                label = if (message is WireMessage.StatusRequest) com.noti.shared.FcmLabel.STATUS_REQUEST
                    else com.noti.shared.FcmLabel.CONFIG,
            )
            if (!res.ok) Diag.log("$what → HTTP ${res.httpCode} (${res.detail.take(40)})")
            res.ok
        } catch (e: Exception) {
            Diag.log("$what → ERROR: ${e.message}")
            false
        }
    }
}
