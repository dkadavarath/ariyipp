package com.noti.sender.ui

import android.view.View
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.noti.sender.R
import com.noti.sender.config.SenderSettings

/** The companion's own safeguards: send limits and the allowlist (see SendLimiter), and screenshot
 *  protection for the pairing screen. None of these can be changed from Main. */
class SecurityActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_security
    override val titleRes = R.string.title_security

    override fun onScreenCreated() {
        val s = SenderSettings.get(this)
        val perHour = findViewById<TextInputEditText>(R.id.et_limit_hour)
        val perDay = findViewById<TextInputEditText>(R.id.et_limit_day)
        val allowlist = findViewById<MaterialSwitch>(R.id.sw_allowlist)
        val protectKeys = findViewById<MaterialSwitch>(R.id.sw_protect_keys)

        perHour.setText(s.sendLimitPerHour.toString())
        perDay.setText(s.sendLimitPerDay.toString())
        allowlist.isChecked = s.allowlistOnly
        protectKeys.isChecked = s.protectKeyScreens

        findViewById<View>(R.id.btn_save).setOnClickListener {
            perHour.text.toString().toIntOrNull()?.let { s.sendLimitPerHour = it }
            perDay.text.toString().toIntOrNull()?.let { s.sendLimitPerDay = it }
            s.allowlistOnly = allowlist.isChecked
            s.protectKeyScreens = protectKeys.isChecked
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
