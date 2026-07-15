package com.noti.logger.ui

import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.config.TriggerMode
import com.noti.logger.work.UploadScheduler

class SyncActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_sync
    override val titleRes = R.string.title_sync

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val rg = findViewById<RadioGroup>(R.id.rg_trigger_mode)
        val interval = findViewById<EditText>(R.id.et_interval_minutes)
        val threshold = findViewById<EditText>(R.id.et_threshold_count)
        val unmetered = findViewById<MaterialSwitch>(R.id.sw_require_unmetered)
        val charging = findViewById<MaterialSwitch>(R.id.sw_require_charging)

        when (s.triggerMode) {
            TriggerMode.PERIODIC -> findViewById<RadioButton>(R.id.rb_periodic).isChecked = true
            TriggerMode.THRESHOLD -> findViewById<RadioButton>(R.id.rb_threshold).isChecked = true
            TriggerMode.MANUAL -> findViewById<RadioButton>(R.id.rb_manual).isChecked = true
        }
        interval.setText(s.intervalMinutes.toString())
        threshold.setText(s.thresholdCount.toString())
        unmetered.isChecked = s.requireUnmetered
        charging.isChecked = s.requireCharging

        findViewById<android.view.View>(R.id.btn_save).setOnClickListener {
            s.triggerMode = when (rg.checkedRadioButtonId) {
                R.id.rb_threshold -> TriggerMode.THRESHOLD
                R.id.rb_manual -> TriggerMode.MANUAL
                else -> TriggerMode.PERIODIC
            }
            val parsed = interval.text.toString().toIntOrNull() ?: 15
            s.intervalMinutes = parsed.coerceAtLeast(15)
            s.thresholdCount = threshold.text.toString().toIntOrNull() ?: s.thresholdCount
            s.requireUnmetered = unmetered.isChecked
            s.requireCharging = charging.isChecked
            UploadScheduler.applyFromSettings(this)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
