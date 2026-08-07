package com.noti.logger.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.messaging.FirebaseMessaging
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.push.QrCodes
import com.noti.shared.MessageCrypto
import com.noti.shared.PairingPayload

/**
 * Configure receiving relayed messages (from sendi): enable inbound, hold the shared AES key, and
 * show this device's FCM token plus a pairing QR that sendi scans.
 */
class RelayReceiveActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_relay
    override val titleRes = R.string.title_relay

    private var currentToken: String = ""

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val enabled = findViewById<MaterialSwitch>(R.id.sw_inbound_enabled)
        val keyField = findViewById<TextInputEditText>(R.id.et_relay_key)
        val tokenView = findViewById<TextView>(R.id.txt_token)
        val qr = findViewById<ImageView>(R.id.img_qr)

        enabled.isChecked = s.pushInboundEnabled
        if (s.relayKey.isBlank()) s.relayKey = MessageCrypto.generateKeyBase64()
        keyField.setText(s.relayKey)

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

        findViewById<View>(R.id.btn_save).setOnClickListener {
            s.pushInboundEnabled = enabled.isChecked
            s.relayKey = keyField.text.toString()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun refreshQr(qr: ImageView, key: String) {
        if (currentToken.isBlank() || key.isBlank()) return
        qr.setImageBitmap(QrCodes.encode(PairingPayload.format(currentToken, key), 600))
    }
}
