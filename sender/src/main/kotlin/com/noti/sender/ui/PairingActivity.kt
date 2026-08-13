package com.noti.sender.ui

import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.noti.sender.R
import com.noti.sender.config.SenderSettings
import com.noti.shared.PairingPayload
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

    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onScanned(it) }
    }

    override fun onScreenCreated() {
        val s = SenderSettings.get(this)
        val token = findViewById<TextInputEditText>(R.id.et_noti_token)
        val relayKey = findViewById<TextInputEditText>(R.id.et_relay_key)
        val fcmEnabled = findViewById<MaterialSwitch>(R.id.sw_fcm_enabled)
        val accept = findViewById<MaterialSwitch>(R.id.sw_accept_commands)

        token.setText(s.notiFcmToken)
        relayKey.setText(s.relayKey)
        fcmEnabled.isChecked = s.fcmEnabled
        accept.isChecked = s.acceptCommands
        updateKeyStatus()

        // This device's own FCM token, so ippu can push send-SMS commands here. Shown as text and as
        // a QR (token + the shared key it holds) for ippu to scan on its Relay screen.
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t ->
                s.myFcmToken = t
                findViewById<android.widget.TextView>(R.id.txt_my_token).text = t
                refreshMyTokenQr(t, relayKey.text?.toString().orEmpty())
            }
        relayKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                refreshMyTokenQr(s.myFcmToken, e?.toString().orEmpty())
            }
        })

        findViewById<MaterialButton>(R.id.btn_import_key).setOnClickListener {
            // Some providers mislabel .json; accept anything and validate the contents ourselves.
            importKey.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        findViewById<MaterialButton>(R.id.btn_scan_qr).setOnClickListener {
            scan.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }
        findViewById<View>(R.id.btn_save).setOnClickListener {
            s.notiFcmToken = token.text.toString()
            s.relayKey = relayKey.text.toString()
            s.fcmEnabled = fcmEnabled.isChecked
            s.acceptCommands = accept.isChecked
            com.noti.sender.SmsSyncWorker.baselineIfNeeded(this) // mark before the first new SMS
            com.noti.sender.TokenAnnounceWorker.enqueue(this) // tell Main our endpoint (no copy-back)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun refreshMyTokenQr(token: String, keyBase64: String) {
        if (token.isBlank()) return
        val qr = findViewById<android.widget.ImageView>(R.id.img_my_token_qr)
        qr.setImageBitmap(QrCodes.encode(PairingPayload.formatReverse(token, keyBase64), 560))
    }

    private fun onScanned(text: String) {
        val root = findViewById<View>(R.id.screen_root)
        val parsed = PairingPayload.parse(text)
        if (parsed == null) {
            com.noti.sender.util.Haptics.reject(root)
            Toast.makeText(this, R.string.scan_bad, Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<TextInputEditText>(R.id.et_noti_token).setText(parsed.first)
        findViewById<TextInputEditText>(R.id.et_relay_key).setText(parsed.second)
        com.noti.sender.util.Haptics.confirm(root)
        Toast.makeText(this, R.string.scan_ok, Toast.LENGTH_SHORT).show()
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
