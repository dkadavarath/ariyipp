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
        ActivityResultContracts.RequestPermission()
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
            requestSms.launch(Manifest.permission.RECEIVE_SMS)
        }

        val container = findViewById<LinearLayout>(R.id.settings_container)
        val inflater = LayoutInflater.from(this)
        fun addRow(titleRes: Int, subRes: Int, cls: Class<*>) {
            val row = inflater.inflate(R.layout.item_settings_row, container, false)
            row.findViewById<TextView>(R.id.row_title).setText(titleRes)
            row.findViewById<TextView>(R.id.row_sub).setText(subRes)
            row.setOnClickListener { startActivity(Intent(this, cls)) }
            container.addView(row)
        }
        addRow(R.string.menu_pairing_title, R.string.menu_pairing_sub, PairingActivity::class.java)
        addRow(R.string.menu_sim_names_title, R.string.menu_sim_names_sub, SimNamesActivity::class.java)
        addRow(R.string.menu_webhook_title, R.string.menu_webhook_sub, WebhookActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        updateSmsStatus()
    }

    private fun updateSmsStatus() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.txt_sms_status)
            .setText(if (granted) R.string.sms_status_granted else R.string.sms_status_denied)
        findViewById<MaterialButton>(R.id.btn_grant_sms).visibility =
            if (granted) View.GONE else View.VISIBLE
    }
}
