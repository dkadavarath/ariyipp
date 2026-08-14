package com.noti.logger.ui

import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.push.NotiConfigSender
import com.noti.logger.work.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one shared webhook: used for this device's own uploads and pushable to the companion. */
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

        fun persist() {
            s.webhookUrl = url.text.toString().trim()
            s.bearerToken = token.text.toString().trim()
            s.authHeaderName = headerName.text.toString().trim().ifBlank { "Authorization" }
            // Prefix is NOT trimmed: the default "Bearer " needs its trailing space.
            s.authHeaderPrefix = headerPrefix.text.toString()
            s.gzipEnabled = gzip.isChecked
            UploadScheduler.applyFromSettings(this)
        }

        findViewById<MaterialButton>(R.id.btn_push).setOnClickListener {
            persist()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { NotiConfigSender.pushWebhook(applicationContext) }
                Toast.makeText(
                    this@ConnectionActivity,
                    if (ok) R.string.cmp_webhook_pushed else R.string.cmp_webhook_push_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        findViewById<android.view.View>(R.id.btn_save).setOnClickListener {
            persist()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
