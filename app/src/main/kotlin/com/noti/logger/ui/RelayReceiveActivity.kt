package com.noti.logger.ui

import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.messaging.FirebaseMessaging
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.push.QrCodes
import com.noti.shared.MessageCrypto
import com.noti.shared.PairingPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Main-side pairing. This device owns the shared key (read-only, with Regenerate) and shows one QR
 * (its token + the key) for the companion to scan. The companion then announces its own endpoint
 * over the encrypted channel, so there is nothing to scan or paste back here.
 */
class RelayReceiveActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_relay
    override val titleRes = R.string.title_relay

    private var currentToken: String = ""

    private val importSa = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onSaPicked(it) }
    }

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val enabled = findViewById<MaterialSwitch>(R.id.sw_inbound_enabled)
        val otpCopy = findViewById<MaterialSwitch>(R.id.sw_otp_copy)
        val suppressSystem = findViewById<MaterialSwitch>(R.id.sw_suppress_system)
        val heartbeat = findViewById<MaterialSwitch>(R.id.sw_heartbeat)
        val keyField = findViewById<TextInputEditText>(R.id.et_relay_key)
        val tokenView = findViewById<TextView>(R.id.txt_token)
        val qr = findViewById<ImageView>(R.id.img_qr)

        enabled.isChecked = s.pushInboundEnabled
        otpCopy.isChecked = s.otpCopyEnabled
        suppressSystem.isChecked = s.suppressSystemNotifActions
        heartbeat.isChecked = s.heartbeatEnabled
        if (s.relayKey.isBlank()) s.relayKey = MessageCrypto.generateKeyBase64()
        keyField.setText(s.relayKey)
        keyField.keyListener = null // read-only (still selectable/copyable) - Main owns the key
        // setKeyListener(null) clears the password transformation, so re-mask now to avoid a
        // one-frame plaintext flash of the key when the screen opens.
        keyField.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        updateSaStatus()

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            currentToken = token
            s.fcmToken = token
            tokenView.text = token
            refreshQr(qr, keyField.text.toString())
        }

        findViewById<MaterialButton>(R.id.btn_generate_key).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.regen_key_title)
                .setMessage(R.string.regen_key_msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.regen_key_yes) { _, _ ->
                    keyField.setText(MessageCrypto.generateKeyBase64())
                    refreshQr(qr, keyField.text.toString())
                }
                .show()
        }

        findViewById<MaterialButton>(R.id.btn_import_sa).setOnClickListener {
            importSa.launch(arrayOf("application/json", "text/*", "*/*"))
        }

        findViewById<View>(R.id.btn_save).setOnClickListener {
            s.pushInboundEnabled = enabled.isChecked
            s.otpCopyEnabled = otpCopy.isChecked
            s.suppressSystemNotifActions = suppressSystem.isChecked
            s.heartbeatEnabled = heartbeat.isChecked
            s.relayKey = keyField.text.toString()
            // Don't touch sndiFcmToken - it's set automatically when the companion announces itself.
            if (heartbeat.isChecked) {
                com.noti.logger.push.Heartbeat.baselineIfNeeded(this)
                com.noti.logger.work.HeartbeatWorker.schedulePeriodic(this)
            } else {
                androidx.core.app.NotificationManagerCompat.from(this)
                    .cancel(com.noti.shared.HeartbeatPolicy.NOTIFICATION_ID)
            }
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun refreshQr(qr: ImageView, key: String) {
        if (currentToken.isBlank() || key.isBlank()) return
        qr.setImageBitmap(QrCodes.encode(PairingPayload.format(currentToken, key), 600))
    }

    private fun onSaPicked(uri: Uri) {
        val content = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (e: Exception) {
            ""
        }
        val valid = try {
            val o = Json.parseToJsonElement(content).jsonObject
            o["private_key"] != null && o["client_email"] != null
        } catch (e: Exception) {
            false
        }
        if (valid) Settings.get(this).serviceAccountJson = content
        updateSaStatus(invalid = !valid)
    }

    private fun updateSaStatus(invalid: Boolean = false) {
        val json = Settings.get(this).serviceAccountJson
        findViewById<TextView>(R.id.txt_sa_status).text = when {
            invalid -> getString(R.string.relay_sa_invalid)
            json.isBlank() -> getString(R.string.relay_sa_none)
            else -> {
                val project = try {
                    Json.parseToJsonElement(json).jsonObject["project_id"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
                getString(R.string.relay_sa_imported, project ?: "?")
            }
        }
    }
}
