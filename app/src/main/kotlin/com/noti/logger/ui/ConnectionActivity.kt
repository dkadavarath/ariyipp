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
import com.noti.shared.WebhookUrlPolicy
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
        // Leave header name/prefix blank at their defaults, so the greyed hint shows rather than a
        // solid value that reads like help text. Defaults are re-applied on save.
        if (s.authHeaderName != "Authorization") headerName.setText(s.authHeaderName)
        if (s.authHeaderPrefix != "Bearer ") headerPrefix.setText(s.authHeaderPrefix)
        gzip.isChecked = s.gzipEnabled

        // @return false (and shows a field error) if the URL isn't https - the caller must not
        // proceed (push/save) in that case, so an unsafe URL is rejected before it's ever stored.
        fun persist(): Boolean {
            val newUrl = url.text.toString().trim()
            if (newUrl.isNotBlank() && !WebhookUrlPolicy.isAllowed(newUrl)) {
                url.error = getString(R.string.webhook_url_must_be_https)
                return false
            }
            s.webhookUrl = newUrl
            s.bearerToken = token.text.toString().trim()
            val name = headerName.text.toString().trim().ifBlank { "Authorization" }
            s.authHeaderName = name
            // Blank prefix means "Bearer " for the standard Authorization header, or a raw token for
            // a custom header. NOT trimmed - the default "Bearer " needs its trailing space.
            val prefix = headerPrefix.text.toString()
            s.authHeaderPrefix = if (prefix.isBlank() && name == "Authorization") "Bearer " else prefix
            s.gzipEnabled = gzip.isChecked
            UploadScheduler.applyFromSettings(this)
            return true
        }

        findViewById<MaterialButton>(R.id.btn_push).setOnClickListener {
            if (!persist()) return@setOnClickListener
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
            if (!persist()) return@setOnClickListener
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
