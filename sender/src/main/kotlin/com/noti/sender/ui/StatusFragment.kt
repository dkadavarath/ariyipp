package com.noti.sender.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.noti.sender.R

/** Status tab: SMS-access state and the grant button. */
class StatusFragment : Fragment(R.layout.fragment_status) {

    private val requestSms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateSmsStatus() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.btn_grant_sms).setOnClickListener {
            // RECEIVE_SMS to relay; SEND_SMS + READ_PHONE_STATE for ippu-driven sends and SIM choice.
            requestSms.launch(
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateSmsStatus()
    }

    private fun updateSmsStatus() {
        val v = view ?: return
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED
        val canReceive = granted(Manifest.permission.RECEIVE_SMS)
        val canSend = granted(Manifest.permission.SEND_SMS)
        v.findViewById<TextView>(R.id.txt_sms_status).setText(
            when {
                canReceive && canSend -> R.string.sms_status_granted
                canReceive -> R.string.sms_status_send_denied
                else -> R.string.sms_status_denied
            }
        )
        v.findViewById<MaterialButton>(R.id.btn_grant_sms).visibility =
            if (canReceive && canSend) View.GONE else View.VISIBLE
    }
}
