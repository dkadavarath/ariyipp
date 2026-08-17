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
import com.noti.sender.push.FirebaseInitCore
import com.noti.shared.GoogleServices
import com.noti.shared.PairingPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Import the service-account key (SAF file picker) and enter noti's token + the shared AES key. */
class PairingActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_pairing
    override val titleRes = R.string.title_pairing

    private val importFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { onFilePicked(it) }
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
        val heartbeat = findViewById<MaterialSwitch>(R.id.sw_heartbeat)

        token.setText(s.notiFcmToken)
        relayKey.setText(s.relayKey)
        // Mask from frame 1 so the key isn't briefly shown in plaintext when the screen opens.
        relayKey.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        fcmEnabled.isChecked = s.fcmEnabled
        accept.isChecked = s.acceptCommands
        heartbeat.isChecked = s.heartbeatEnabled
        updateFbStatus()
        updateKeyStatus()
        refreshMyToken()

        findViewById<MaterialButton>(R.id.btn_import_key).setOnClickListener {
            // Some providers mislabel .json; accept anything and validate the contents ourselves.
            importFiles.launch(arrayOf("application/json", "text/*", "*/*"))
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
            s.heartbeatEnabled = heartbeat.isChecked
            if (!heartbeat.isChecked) {
                androidx.core.app.NotificationManagerCompat.from(this)
                    .cancel(com.noti.shared.HeartbeatPolicy.NOTIFICATION_ID)
            }
            com.noti.sender.SmsSyncWorker.baselineIfNeeded(this) // mark before the first new SMS
            com.noti.sender.TokenAnnounceWorker.enqueue(this) // tell Main our endpoint (no copy-back)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
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

    /** Refreshes this device's own FCM token (announced to Main after pairing) if Firebase is
     *  configured; silently no-ops otherwise rather than crashing on an unconfigured FirebaseMessaging. */
    private fun refreshMyToken() {
        if (!FirebaseInitCore.initFrom(this, SenderSettings.get(this).firebaseConfigJson)) return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t -> SenderSettings.get(this).myFcmToken = t }
    }

    /** Reads one picked file and routes it by content: a google-services.json updates the Firebase
     *  config, a service-account key updates the send credential. Either can be re-imported alone. */
    private fun onFilePicked(uri: Uri) {
        val content = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (e: Exception) {
            ""
        }
        val s = SenderSettings.get(this)
        when {
            GoogleServices.looksLikeGoogleServices(content) -> {
                val valid = try {
                    GoogleServices.parse(content, packageName); true
                } catch (e: Exception) {
                    false
                }
                if (valid) s.firebaseConfigJson = content
                updateFbStatus(invalid = !valid)
                if (valid) refreshMyToken()
            }
            isServiceAccountKey(content) -> {
                s.serviceAccountJson = content
                updateKeyStatus()
            }
            else -> Toast.makeText(this, R.string.key_status_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isServiceAccountKey(content: String): Boolean = try {
        val o = Json.parseToJsonElement(content).jsonObject
        o["private_key"] != null && o["client_email"] != null
    } catch (e: Exception) {
        false
    }

    private fun updateFbStatus(invalid: Boolean = false) {
        val json = SenderSettings.get(this).firebaseConfigJson
        findViewById<TextView>(R.id.txt_fb_status).text = when {
            invalid -> getString(R.string.fb_status_invalid)
            json.isBlank() -> getString(R.string.fb_status_none)
            else -> {
                val project = try { GoogleServices.parse(json, packageName).projectId } catch (e: Exception) { null }
                getString(R.string.fb_status_imported, project ?: "?")
            }
        }
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
