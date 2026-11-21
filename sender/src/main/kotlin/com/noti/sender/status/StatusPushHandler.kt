package com.noti.sender.status

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts an inbound push and returns it iff it's one of the two status messages Main sends: a
 * [WireMessage.StatusRequest] (open/renew/close the readout stream) or a [WireMessage.StatusConfig]
 * (the alert thresholds). Both in one handler because both are Main → Companion and both arrive on
 * the same channel - one decrypt serves either. Kept free of Firebase types so it's unit-testable.
 *
 * Unlike the webhook config there is no separate opt-in switch here: [SenderSettings.statusSharing
 * Enabled] is the one gate, and it covers both answering requests and raising alerts. A config push
 * only ever adjusts thresholds - it can't redirect data anywhere, which is what made the webhook
 * config's kill-switch necessary.
 */
object StatusPushHandler {

    const val PAYLOAD_KEY = "payload"

    fun parse(context: Context, data: Map<String, String>): WireMessage? {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val s = SenderSettings.get(context)
        if (!s.statusSharingEnabled) return null
        val key = s.relayKey.takeIf { it.isNotBlank() } ?: return null
        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            return null
        }
        val wire = try {
            Wire.decodeFrom(plaintext, com.noti.shared.Wire.Direction.MAIN_TO_COMPANION)
        } catch (e: Exception) {
            return null
        }
        return when (wire) {
            is WireMessage.StatusRequest -> wire
            is WireMessage.StatusConfig ->
                // Reject a stale/replayed config, exactly as WebhookConfigHandler does: only a
                // version newer than the last applied can take effect, so a captured older push
                // can't quietly restore a threshold the user has since changed.
                if (wire.configVersion <= s.lastStatusConfigVersion) null else wire
            else -> null
        }
    }

    /** Apply pushed thresholds. Values are clamped by the setters, so an out-of-range or hostile
     *  config lands on a sane bound instead of e.g. a 1-second poll that would flatten the battery. */
    fun apply(context: Context, cfg: WireMessage.StatusConfig) {
        val s = SenderSettings.get(context)
        val batteryChanged = s.statusBatteryPct != DeviceStatusPolicy.clampBatteryPct(cfg.batteryPct)
        s.statusAlertsEnabled = cfg.alertsEnabled
        s.statusBatteryPct = cfg.batteryPct
        s.statusWifiLossMinutes = cfg.wifiLossMinutes
        s.statusPollSeconds = cfg.pollSeconds
        s.lastStatusConfigVersion = cfg.configVersion
        // A raised battery threshold should be able to alert immediately for a battery that is
        // already below the new line, rather than waiting for the next discharge episode.
        if (batteryChanged) s.batteryAlertArmed = true
        DeviceMonitor.evaluate(context)
    }
}
