package com.noti.logger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.push.Heartbeat
import com.noti.logger.work.HeartbeatWorker
import com.noti.logger.work.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status tab: permissions, sync status, data counts, and Sync now. */
class StatusFragment : Fragment(R.layout.fragment_status) {

    private lateinit var tvNotification: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvLastUpload: TextView
    private lateinit var tvData: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvNotification = view.findViewById(R.id.tv_notification_status)
        tvBattery = view.findViewById(R.id.tv_battery_status)
        tvLastUpload = view.findViewById(R.id.tv_last_upload)
        tvData = view.findViewById(R.id.tv_data)

        view.findViewById<View>(R.id.btn_grant_notification).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        view.findViewById<View>(R.id.btn_ignore_battery).setOnClickListener { ignoreBatteryOptimization() }
        view.findViewById<View>(R.id.btn_sync_now).setOnClickListener {
            UploadScheduler.enqueueOneShot(requireContext())
            Toast.makeText(requireContext(), R.string.sync_enqueued, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btn_hb_retry).setOnClickListener {
            HeartbeatWorker.retryNow(requireContext())
            Toast.makeText(requireContext(), R.string.hb_retry_now, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshStatus()
        refreshHeartbeatBanner()
    }

    private fun refreshHeartbeatBanner() {
        val v = view ?: return
        val ctx = requireContext().applicationContext
        val show = Heartbeat.isDisconnected(ctx)
        v.findViewById<View>(R.id.card_hb_banner).visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            v.findViewById<TextView>(R.id.tv_hb_title).text =
                getString(R.string.hb_banner_title, Heartbeat.peerLabel(ctx))
        }
    }

    private fun refreshPermissions() {
        val granted = isNotificationAccessGranted()
        tvNotification.text = getString(
            R.string.notification_access_status,
            if (granted) getString(R.string.granted) else getString(R.string.not_granted)
        )
        val exempt = isBatteryOptimizationExempt()
        tvBattery.text = getString(
            R.string.battery_optimization_status,
            if (exempt) getString(R.string.exempt) else getString(R.string.not_exempt)
        )
    }

    private fun refreshStatus() {
        val ctx = requireContext().applicationContext
        val dao = NotiDatabase.get(ctx).notificationDao()
        val s = Settings.get(ctx)
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val total = dao.totalCount()
            val pending = dao.pendingCount()
            val lastAt = s.lastUploadAtMs
            val lastResult = s.lastUploadResult ?: "-"
            val lastStr = if (lastAt == 0L) getString(R.string.never) else df.format(Date(lastAt))
            val deviceId = s.deviceId
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                tvLastUpload.text = getString(R.string.status_last_upload, lastStr, lastResult)
                tvData.text = buildString {
                    appendLine(getString(R.string.status_total, total))
                    appendLine(getString(R.string.status_pending, pending))
                    append(getString(R.string.status_device_id, deviceId))
                }
            }
        }
    }

    private fun ignoreBatteryOptimization() {
        try {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${requireContext().packageName}")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Exception) {
            }
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val listeners = AndroidSettings.Secure.getString(
            requireContext().contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return listeners.contains(requireContext().packageName)
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }
}
