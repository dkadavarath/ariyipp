package com.noti.logger.ui

import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.push.NotiConfigSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main-side editor for the companion's webhook, plus a Push button that sends it over the encrypted
 * channel — so the hard-to-reach companion can be configured remotely. The companion keeps posting
 * to the webhook directly; this only sets what that webhook is.
 */
class CompanionWebhookActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_companion_webhook
    override val titleRes = R.string.menu_cmp_webhook_title

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val enabled = findViewById<MaterialSwitch>(R.id.sw_cmp_n8n_enabled)
        val url = findViewById<TextInputEditText>(R.id.et_cmp_url)
        val header = findViewById<TextInputEditText>(R.id.et_cmp_header)
        val prefix = findViewById<TextInputEditText>(R.id.et_cmp_prefix)
        val token = findViewById<TextInputEditText>(R.id.et_cmp_token)

        enabled.isChecked = s.companionN8nEnabled
        url.setText(s.companionN8nUrl)
        header.setText(s.companionN8nHeaderName)
        prefix.setText(s.companionN8nHeaderPrefix)
        token.setText(s.companionN8nToken)

        fun persist() {
            s.companionN8nEnabled = enabled.isChecked
            s.companionN8nUrl = url.text.toString()
            s.companionN8nHeaderName = header.text.toString()
            s.companionN8nHeaderPrefix = prefix.text.toString()
            s.companionN8nToken = token.text.toString()
        }

        findViewById<MaterialButton>(R.id.btn_push).setOnClickListener {
            persist()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { NotiConfigSender.pushWebhook(applicationContext) }
                Toast.makeText(
                    this@CompanionWebhookActivity,
                    if (ok) R.string.cmp_webhook_pushed else R.string.cmp_webhook_push_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        findViewById<View>(R.id.btn_save).setOnClickListener {
            persist()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
