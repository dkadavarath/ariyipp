package com.noti.logger.ui

import android.widget.EditText
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.work.UploadScheduler

class ConnectionActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_connection
    override val titleRes = R.string.title_connection

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val url = findViewById<EditText>(R.id.et_webhook_url)
        val token = findViewById<EditText>(R.id.et_bearer_token)
        val headerName = findViewById<EditText>(R.id.et_auth_header_name)
        val headerPrefix = findViewById<EditText>(R.id.et_auth_header_prefix)
        val gzip = findViewById<MaterialSwitch>(R.id.sw_gzip)

        url.setText(s.webhookUrl)
        token.setText(s.bearerToken)
        headerName.setText(s.authHeaderName)
        headerPrefix.setText(s.authHeaderPrefix)
        gzip.isChecked = s.gzipEnabled

        findViewById<android.view.View>(R.id.btn_save).setOnClickListener {
            s.webhookUrl = url.text.toString().trim()
            s.bearerToken = token.text.toString().trim()
            s.authHeaderName = headerName.text.toString().trim().ifBlank { "Authorization" }
            // Prefix is NOT trimmed: the default "Bearer " needs its trailing space.
            s.authHeaderPrefix = headerPrefix.text.toString()
            s.gzipEnabled = gzip.isChecked
            UploadScheduler.applyFromSettings(this)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
