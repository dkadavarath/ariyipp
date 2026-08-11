package com.noti.sender.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.noti.sender.KeepAliveService
import com.noti.sender.R
import com.noti.sender.SmsSyncWorker

/** Status tab: SMS-access state, battery-optimization exemption, keep-alive, and a missed-SMS sync. */
class StatusFragment : Fragment(R.layout.fragment_status) {

    private val requestSms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermsChanged() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.btn_grant_sms).setOnClickListener {
            requestSms.launch(
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            )
        }
        view.findViewById<MaterialButton>(R.id.btn_battery).setOnClickListener { requestBatteryExemption() }
        view.findViewById<MaterialButton>(R.id.btn_sync).setOnClickListener {
            SmsSyncWorker.syncNow(requireContext())
            Toast.makeText(requireContext(), R.string.sync_started, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // Keep the relay warm and the backstop scheduled whenever the app is opened.
        KeepAliveService.ensureRunning(requireContext())
        SmsSyncWorker.schedulePeriodic(requireContext())
    }

    private fun onPermsChanged() {
        updateStatus()
        KeepAliveService.ensureRunning(requireContext())
        SmsSyncWorker.schedulePeriodic(requireContext())
    }

    private fun updateStatus() {
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

        val exempt = isBatteryExempt()
        v.findViewById<TextView>(R.id.txt_battery_status).setText(
            if (exempt) R.string.battery_exempt else R.string.battery_optimized
        )
        v.findViewById<MaterialButton>(R.id.btn_battery).visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun isBatteryExempt(): Boolean {
        val pm = requireContext().getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (isBatteryExempt()) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${requireContext().packageName}"))
            )
        } catch (e: Exception) {
            // Fall back to the battery-optimization settings list.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
