package com.noti.sender.ui

import android.view.View
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.noti.sender.R
import com.noti.sender.config.SenderSettings

/** Names for the two SIM slots, shown in the relayed message (skips the READ_PHONE_STATE limitation). */
class SimNamesActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_sim_names
    override val titleRes = R.string.title_sim_names

    override fun onScreenCreated() {
        val s = SenderSettings.get(this)
        val sim1 = findViewById<TextInputEditText>(R.id.et_sim1_name)
        val sim2 = findViewById<TextInputEditText>(R.id.et_sim2_name)

        sim1.setText(s.sim1Name)
        sim2.setText(s.sim2Name)

        findViewById<View>(R.id.btn_save).setOnClickListener {
            s.sim1Name = sim1.text.toString()
            s.sim2Name = sim2.text.toString()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
