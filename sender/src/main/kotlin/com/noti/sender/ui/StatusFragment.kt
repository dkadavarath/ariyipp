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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noti.sender.KeepAliveService
import com.noti.sender.R
import com.noti.sender.RepushWorker
import com.noti.sender.SenderPipeline
import com.noti.sender.SmsSyncWorker
import com.noti.sender.config.SenderSettings
import androidx.core.app.NotificationManagerCompat
import com.noti.shared.HeartbeatPolicy

/** Status tab: SMS-access state, battery-optimization exemption, keep-alive, and a missed-SMS sync. */
class StatusFragment : Fragment(R.layout.cmp_fragment_status) {

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
            SmsSyncWorker.scanNow(requireContext())
            Toast.makeText(requireContext(), R.string.sync_started, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.btn_repush).setOnClickListener { confirmRepush() }
        view.findViewById<MaterialButton>(R.id.btn_hb_retry).setOnClickListener { retryHeartbeat() }
    }

    /** Ask the (app) heartbeat engine to force a check, and clear the warning notification now. */
    private fun retryHeartbeat() {
        val ctx = requireContext()
        ctx.sendBroadcast(Intent(HeartbeatPolicy.ACTION_RETRY).setPackage(ctx.packageName))
        NotificationManagerCompat.from(ctx).cancel(HeartbeatPolicy.NOTIFICATION_ID)
        Toast.makeText(ctx, R.string.hb_retry_now, Toast.LENGTH_SHORT).show()
    }

    private fun refreshHeartbeatBanner() {
        val v = view ?: return
        val s = SenderSettings.get(requireContext())
        val disconnected = s.peerPaired() &&
            HeartbeatPolicy.isStale(s.lastPeerBeatAtMs, System.currentTimeMillis())
        v.findViewById<View>(R.id.card_hb_banner).visibility =
            if (disconnected) View.VISIBLE else View.GONE
    }

    private fun confirmRepush() {
        val ctx = requireContext()
        val readSms = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (!readSms) {
            Toast.makeText(ctx, R.string.repush_need_read, Toast.LENGTH_LONG).show(); return
        }
        if (!SenderPipeline.isConfigured(SenderSettings.get(ctx))) {
            Toast.makeText(ctx, R.string.repush_need_pairing, Toast.LENGTH_LONG).show(); return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.repush_confirm_title)
            .setMessage(R.string.repush_confirm_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.repush_confirm_yes) { _, _ ->
                RepushWorker.start(ctx)
                Toast.makeText(ctx, R.string.repush_started, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshHeartbeatBanner()
        // Keep the relay warm and the backstop scheduled whenever the app is opened.
        KeepAliveService.ensureRunning(requireContext())
        SmsSyncWorker.baselineIfNeeded(requireContext())
        SmsSyncWorker.schedulePeriodic(requireContext())
    }

    private fun onPermsChanged() {
        updateStatus()
        KeepAliveService.ensureRunning(requireContext())
        SmsSyncWorker.baselineIfNeeded(requireContext())
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
