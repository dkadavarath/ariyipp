package com.noti.logger.ui

import android.widget.EditText
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.logger.R
import com.noti.logger.config.Settings

class PrivacyActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_privacy
    override val titleRes = R.string.title_privacy

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val captureBody = findViewById<MaterialSwitch>(R.id.sw_capture_body)
        val keywords = findViewById<EditText>(R.id.et_excluded_keywords)
        val dedupe = findViewById<EditText>(R.id.et_dedupe_window)
        val retention = findViewById<EditText>(R.id.et_retention_days)
        val protectKeys = findViewById<MaterialSwitch>(R.id.sw_protect_keys)
        val protectMessages = findViewById<MaterialSwitch>(R.id.sw_protect_messages)
        protectKeys.isChecked = s.protectKeyScreens
        protectMessages.isChecked = s.protectMessages

        captureBody.isChecked = s.captureBody
        keywords.setText(s.excludedKeywords.joinToString("\n"))
        dedupe.setText(s.dedupeWindowSeconds.toString())
        retention.setText(s.retentionDays.toString())

        findViewById<android.view.View>(R.id.btn_save).setOnClickListener {
            s.captureBody = captureBody.isChecked
            s.excludedKeywords = keywords.text.toString()
                .lines().map { it.trim() }.filter { it.isNotBlank() }
            s.dedupeWindowSeconds = dedupe.text.toString().toIntOrNull() ?: s.dedupeWindowSeconds
            s.retentionDays = retention.text.toString().toIntOrNull() ?: s.retentionDays
            s.protectKeyScreens = protectKeys.isChecked
            s.protectMessages = protectMessages.isChecked
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
