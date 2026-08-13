package com.noti.sender.ui

import android.view.View
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.noti.sender.R
import com.noti.sender.config.SenderSettings

/** Optional plaintext forwarding of each SMS to the n8n webhook, using noti's payload schema. */
class WebhookActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_webhook
    override val titleRes = R.string.title_webhook

    override fun onScreenCreated() {
        val s = SenderSettings.get(this)
        val url = findViewById<TextInputEditText>(R.id.et_n8n_url)
        val headerName = findViewById<TextInputEditText>(R.id.et_header_name)
        val headerPrefix = findViewById<TextInputEditText>(R.id.et_header_prefix)
        val token = findViewById<TextInputEditText>(R.id.et_auth_token)
        val enabled = findViewById<MaterialSwitch>(R.id.sw_n8n_enabled)

        url.setText(s.n8nUrl)
        headerName.setText(s.n8nAuthHeaderName)
        headerPrefix.setText(s.n8nAuthHeaderPrefix)
        token.setText(s.n8nToken)
        enabled.isChecked = s.n8nEnabled

        findViewById<View>(R.id.btn_save).setOnClickListener {
            s.n8nUrl = url.text.toString()
            s.n8nAuthHeaderName = headerName.text.toString()
            s.n8nAuthHeaderPrefix = headerPrefix.text.toString()
            s.n8nToken = token.text.toString()
            s.n8nEnabled = enabled.isChecked
            com.noti.sender.SmsSyncWorker.baselineIfNeeded(this) // mark before the first new SMS
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
