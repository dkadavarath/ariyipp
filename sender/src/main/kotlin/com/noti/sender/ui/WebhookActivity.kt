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
        val acceptRemote = findViewById<MaterialSwitch>(R.id.sw_accept_remote_config)

        url.setText(s.n8nUrl)
        token.setText(s.n8nToken)
        // Leave header name/prefix blank when they're at their defaults, so the greyed hint shows
        // instead of a solid value that reads like help text. Defaults are re-applied on save.
        if (s.n8nAuthHeaderName != "Authorization") headerName.setText(s.n8nAuthHeaderName)
        if (s.n8nAuthHeaderPrefix != "Bearer ") headerPrefix.setText(s.n8nAuthHeaderPrefix)
        enabled.isChecked = s.n8nEnabled
        acceptRemote.isChecked = s.acceptRemoteConfig

        findViewById<View>(R.id.btn_save).setOnClickListener {
            s.n8nUrl = url.text.toString()
            s.n8nToken = token.text.toString()
            val name = headerName.text.toString().trim().ifBlank { "Authorization" }
            s.n8nAuthHeaderName = name
            // Blank prefix means "Bearer " for the standard Authorization header, or a raw token for
            // a custom header (e.g. "key"). Not trimmed — the default "Bearer " needs its space.
            val prefix = headerPrefix.text.toString()
            s.n8nAuthHeaderPrefix = if (prefix.isBlank() && name == "Authorization") "Bearer " else prefix
            s.n8nEnabled = enabled.isChecked
            s.acceptRemoteConfig = acceptRemote.isChecked
            com.noti.sender.SmsSyncWorker.baselineIfNeeded(this) // mark before the first new SMS
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
