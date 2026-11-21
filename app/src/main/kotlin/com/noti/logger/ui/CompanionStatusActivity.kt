package com.noti.logger.ui

import android.view.View
import android.widget.EditText
import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.push.NotiStatusSender

/**
 * Where the companion-status thresholds are set. They live here because this is the phone that gets
 * alerted, but they are *evaluated* on the companion - so saving also pushes them across. If the
 * push fails the values are still saved locally and the user is told plainly that the companion is
 * still running on its previous thresholds, rather than being left to assume the change landed.
 */
class CompanionStatusActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_companion_status
    override val titleRes = R.string.title_companion_status

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val enabled = findViewById<MaterialSwitch>(R.id.sw_cs_enabled)
        val alerts = findViewById<MaterialSwitch>(R.id.sw_cs_alerts)
        val labels = findViewById<MaterialSwitch>(R.id.sw_cs_labels)
        val battery = findViewById<EditText>(R.id.et_cs_battery)
        val wifi = findViewById<EditText>(R.id.et_cs_wifi)
        val poll = findViewById<EditText>(R.id.et_cs_poll)

        enabled.isChecked = s.companionStatusEnabled
        alerts.isChecked = s.companionAlertsEnabled
        labels.isChecked = s.companionStatusLabels
        battery.setText(s.companionBatteryPct.toString())
        wifi.setText(s.companionWifiLossMinutes.toString())
        poll.setText(s.companionPollSeconds.toString())

        // Preferences save as they change. The thresholds still have to reach the companion, but
        // that is a network round-trip, so it is done once on the way out rather than on every
        // keystroke - see onPause.
        enabled.setOnCheckedChangeListener { _, _ -> commit() }
        alerts.setOnCheckedChangeListener { _, _ -> commit() }
        labels.setOnCheckedChangeListener { _, _ -> commit() }
        for (field in listOf(battery, wifi, poll)) {
            field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        }
    }

    override fun onPause() {
        super.onPause()
        commit()
        // Thresholds are evaluated on the companion, so a change here is not in force until it
        // lands there. Pushed on the way out rather than per keystroke, and only when something
        // actually changed - a failed push is reported next time this screen is opened, via the
        // version the companion is still running.
        if (pushNeeded) {
            pushNeeded = false
            val app = applicationContext
            Thread { NotiStatusSender.pushConfig(app) }.start()
        }
    }

    /** Set whenever a pushed-down value changes, so leaving the screen sends exactly one config. */
    private var pushNeeded = false

    private fun commit() {
        val s = Settings.get(this)
        val before = listOf(s.companionAlertsEnabled, s.companionBatteryPct, s.companionWifiLossMinutes, s.companionPollSeconds)
        s.companionStatusEnabled = findViewById<MaterialSwitch>(R.id.sw_cs_enabled).isChecked
        s.companionAlertsEnabled = findViewById<MaterialSwitch>(R.id.sw_cs_alerts).isChecked
        // Purely local presentation - the companion neither knows nor cares how its readings are
        // drawn, so this one is deliberately not part of the pushed config.
        s.companionStatusLabels = findViewById<MaterialSwitch>(R.id.sw_cs_labels).isChecked
        s.companionBatteryPct = text(R.id.et_cs_battery)?.toIntOrNull() ?: s.companionBatteryPct
        s.companionWifiLossMinutes = text(R.id.et_cs_wifi)?.toIntOrNull() ?: s.companionWifiLossMinutes
        s.companionPollSeconds = text(R.id.et_cs_poll)?.toIntOrNull() ?: s.companionPollSeconds
        val after = listOf(s.companionAlertsEnabled, s.companionBatteryPct, s.companionWifiLossMinutes, s.companionPollSeconds)
        if (before != after) pushNeeded = true
    }

    private fun text(id: Int): String? = findViewById<EditText>(id)?.text?.toString()?.trim()
}
