package com.noti.sender.ui

import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.sender.R
import com.noti.sender.StatusWatchWorker
import com.noti.sender.config.SenderSettings
import com.noti.sender.status.DeviceMonitor
import com.noti.sender.status.StatusCollector

/**
 * The companion's own view of what it shares with Main: the master switch, the reading Main would
 * currently receive, and the thresholds Main has pushed down.
 *
 * The thresholds are deliberately read-only here. They are Main's to set - having them editable on
 * both ends would mean two sources of truth for the same number and a race over which push wins.
 * Showing them is still worth it, so someone standing at the companion can see what it will alert
 * on without going to fetch the other phone.
 */
class DeviceStatusActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_device_status
    override val titleRes = R.string.title_device_status

    override fun onScreenCreated() {
        val s = SenderSettings.get(this)
        val sharing = findViewById<MaterialSwitch>(R.id.sw_status_sharing)
        sharing.isChecked = s.statusSharingEnabled
        sharing.setOnCheckedChangeListener { _, checked ->
            s.statusSharingEnabled = checked
            if (checked) {
                StatusWatchWorker.schedule(this)
                DeviceMonitor.start(this)
            } else {
                StatusWatchWorker.cancel(this)
                DeviceMonitor.stop(this)
            }
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val s = SenderSettings.get(this)
        val snap = StatusCollector.snapshot(this)

        fun unknown(): String = getString(R.string.status_value_unknown)

        val networkValue = getString(
            when {
                snap.wifi -> R.string.device_status_wifi
                snap.mobileData -> R.string.device_status_mobile
                else -> R.string.device_status_offline
            }
        )
        KeyValueList.fill(
            findViewById(R.id.tv_status_snapshot),
            listOf(
                getString(R.string.device_status_battery_label) to getString(
                    R.string.device_status_battery_value,
                    if (snap.batteryPct < 0) unknown() else "${snap.batteryPct}%",
                    getString(if (snap.charging) R.string.device_status_charging else R.string.device_status_on_battery),
                ),
                getString(R.string.device_status_signal_label) to getString(
                    R.string.device_status_signal_value,
                    if (snap.signalLevel < 0) unknown() else getString(R.string.device_status_bars, snap.signalLevel),
                    snap.networkType.ifBlank { unknown() },
                ),
                getString(R.string.device_status_network_label) to networkValue,
                getString(R.string.device_status_vpn_label) to getString(if (snap.vpn) R.string.on else R.string.off),
            ),
        )

        KeyValueList.fill(
            findViewById(R.id.tv_status_thresholds),
            if (!s.statusAlertsEnabled) {
                listOf(getString(R.string.device_status_alerts_label) to getString(R.string.off))
            } else {
                listOf(
                    getString(R.string.device_status_battery_threshold_label) to
                        getString(R.string.device_status_battery_threshold_value, s.statusBatteryPct),
                    getString(R.string.device_status_wifi_threshold_label) to
                        getString(R.string.device_status_wifi_threshold_value, s.statusWifiLossMinutes),
                )
            },
        )
    }
}
