package com.noti.sender.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.noti.sender.R

/** sndi home: SMS-access status + a settings menu opening focused sub-screens (noti's pattern). */
class MainActivity : AppCompatActivity() {

    private val requestSms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateSmsStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.screen_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        setSupportActionBar(findViewById(R.id.toolbar))

        findViewById<MaterialButton>(R.id.btn_grant_sms).setOnClickListener {
            // RECEIVE_SMS to relay; SEND_SMS + READ_PHONE_STATE for noti-driven sends and SIM choice.
            requestSms.launch(
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                )
            )
        }

        val container = findViewById<LinearLayout>(R.id.settings_container)
        val inflater = LayoutInflater.from(this)
        fun addRow(titleRes: Int, subRes: Int, iconRes: Int, cls: Class<*>) {
            val row = inflater.inflate(R.layout.item_settings_row, container, false)
            row.findViewById<TextView>(R.id.row_title).setText(titleRes)
            row.findViewById<TextView>(R.id.row_sub).setText(subRes)
            row.findViewById<android.widget.ImageView>(R.id.row_icon).setImageResource(iconRes)
            row.setOnClickListener { startActivity(Intent(this, cls)) }
            container.addView(row)
        }
        addRow(R.string.menu_pairing_title, R.string.menu_pairing_sub, R.drawable.ic_qr_code, PairingActivity::class.java)
        addRow(R.string.menu_sim_names_title, R.string.menu_sim_names_sub, R.drawable.ic_sim, SimNamesActivity::class.java)
        addRow(R.string.menu_webhook_title, R.string.menu_webhook_sub, R.drawable.ic_connection, WebhookActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        updateSmsStatus()
    }

    private fun updateSmsStatus() {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val canReceive = granted(Manifest.permission.RECEIVE_SMS)   // relay incoming SMS to ippu
        val canSend = granted(Manifest.permission.SEND_SMS)         // send SMS on command from ippu
        findViewById<TextView>(R.id.txt_sms_status).setText(
            when {
                canReceive && canSend -> R.string.sms_status_granted
                canReceive -> R.string.sms_status_send_denied // relay works, reverse send won't
                else -> R.string.sms_status_denied
            }
        )
        // Keep the button until BOTH are granted, so an existing install that only has RECEIVE_SMS
        // can still be prompted for SEND_SMS (needed for ippu-driven sends).
        findViewById<MaterialButton>(R.id.btn_grant_sms).visibility =
            if (canReceive && canSend) View.GONE else View.VISIBLE
    }
}
