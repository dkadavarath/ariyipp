package com.noti.logger.ui

import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.messaging.FirebaseMessaging
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.push.QrCodes
import com.noti.shared.MessageCrypto
import com.noti.shared.PairingPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Configure the relay with sndi, both directions:
 *  - Receive: enable inbound, hold the shared AES key, show this device's token + a pairing QR.
 *  - Send (reverse): import the same service-account key and hold sndi's token, so noti can push
 *    send-SMS commands.
 */
class RelayReceiveActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_relay
    override val titleRes = R.string.title_relay

    private var currentToken: String = ""

    private val importSa = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onSaPicked(it) }
    }

    private val scanAriy = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onAriyScanned(it) }
    }

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val enabled = findViewById<MaterialSwitch>(R.id.sw_inbound_enabled)
        val otpCopy = findViewById<MaterialSwitch>(R.id.sw_otp_copy)
        val suppressSystem = findViewById<MaterialSwitch>(R.id.sw_suppress_system)
        val keyField = findViewById<TextInputEditText>(R.id.et_relay_key)
        val tokenView = findViewById<TextView>(R.id.txt_token)
        val qr = findViewById<ImageView>(R.id.img_qr)
        val sndiToken = findViewById<TextInputEditText>(R.id.et_sndi_token)

        enabled.isChecked = s.pushInboundEnabled
        otpCopy.isChecked = s.otpCopyEnabled
        suppressSystem.isChecked = s.suppressSystemNotifActions
        if (s.relayKey.isBlank()) s.relayKey = MessageCrypto.generateKeyBase64()
        keyField.setText(s.relayKey)
        sndiToken.setText(s.sndiFcmToken)
        updateSaStatus()

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            currentToken = token
            s.fcmToken = token
            tokenView.text = token
            refreshQr(qr, keyField.text.toString())
        }

        findViewById<MaterialButton>(R.id.btn_generate_key).setOnClickListener {
            keyField.setText(MessageCrypto.generateKeyBase64())
            refreshQr(qr, keyField.text.toString())
        }

        findViewById<MaterialButton>(R.id.btn_import_sa).setOnClickListener {
            importSa.launch(arrayOf("application/json", "text/*", "*/*"))
        }

        findViewById<MaterialButton>(R.id.btn_scan_ariy).setOnClickListener {
            scanAriy.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.relay_scan_ariy_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }

        findViewById<View>(R.id.btn_save).setOnClickListener {
            s.pushInboundEnabled = enabled.isChecked
            s.otpCopyEnabled = otpCopy.isChecked
            s.suppressSystemNotifActions = suppressSystem.isChecked
            s.relayKey = keyField.text.toString()
            s.sndiFcmToken = sndiToken.text.toString()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun onAriyScanned(text: String) {
        val parsed = PairingPayload.parseReverse(text)
        if (parsed == null) {
            Toast.makeText(this, R.string.relay_scan_ariy_bad, Toast.LENGTH_SHORT).show()
            return
        }
        val (token, key) = parsed
        findViewById<TextInputEditText>(R.id.et_sndi_token).setText(token)
        // If ariy also carried the shared key and we don't have one yet, adopt it so a single scan
        // fully sets up the reverse direction.
        val keyField = findViewById<TextInputEditText>(R.id.et_relay_key)
        if (key.isNotBlank() && keyField.text.isNullOrBlank()) keyField.setText(key)
        Toast.makeText(this, R.string.relay_scan_ariy_ok, Toast.LENGTH_SHORT).show()
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
