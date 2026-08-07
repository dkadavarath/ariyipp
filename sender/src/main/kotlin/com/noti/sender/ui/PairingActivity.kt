package com.noti.sender.ui

import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.noti.sender.R
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Import the service-account key (SAF file picker) and enter noti's token + the shared AES key. */
class PairingActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_pairing
    override val titleRes = R.string.title_pairing

    private val importKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onKeyPicked(it) }
    }

    override fun onScreenCreated() {
        val s = SenderSettings.get(this)
        val token = findViewById<TextInputEditText>(R.id.et_noti_token)
        val relayKey = findViewById<TextInputEditText>(R.id.et_relay_key)
        val fcmEnabled = findViewById<MaterialSwitch>(R.id.sw_fcm_enabled)

        token.setText(s.notiFcmToken)
        relayKey.setText(s.relayKey)
        fcmEnabled.isChecked = s.fcmEnabled
        updateKeyStatus()

        findViewById<MaterialButton>(R.id.btn_import_key).setOnClickListener {
            // Some providers mislabel .json; accept anything and validate the contents ourselves.
            importKey.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        findViewById<MaterialButton>(R.id.btn_generate_key).setOnClickListener {
            relayKey.setText(MessageCrypto.generateKeyBase64())
            Toast.makeText(this, R.string.key_generated, Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btn_save).setOnClickListener {
            s.notiFcmToken = token.text.toString()
            s.relayKey = relayKey.text.toString()
            s.fcmEnabled = fcmEnabled.isChecked
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun onKeyPicked(uri: Uri) {
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
        if (valid) SenderSettings.get(this).serviceAccountJson = content
        updateKeyStatus(invalid = !valid)
    }

    private fun updateKeyStatus(invalid: Boolean = false) {
        val json = SenderSettings.get(this).serviceAccountJson
        findViewById<TextView>(R.id.txt_key_status).text = when {
            invalid -> getString(R.string.key_status_invalid)
            json.isBlank() -> getString(R.string.key_status_none)
            else -> {
                val project = try {
                    Json.parseToJsonElement(json).jsonObject["project_id"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
                getString(R.string.key_status_imported, project ?: "?")
            }
        }
    }
}
