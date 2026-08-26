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
import java.util.concurrent.TimeUnit

/**
 * Last-known Status screen state, kept warm across tab switches - MainActivity recreates this
 * Fragment on every switch, so without this, returning to Status would show blank counts while Room
 * re-queries from scratch instead of the real numbers it just showed moments ago.
 *
 * [permissionsNeedRefresh] gates the permission checks specifically: they can't change without
 * leaving the app (system Settings), so re-checking them on every in-app tab switch is pure waste -
 * [MainActivity.onResume] (a real app-level resume, unlike a Fragment swap) is what sets this back
 * to true; a plain tab switch to Status leaves it false and just repaints the last-known state below.
 */
object StatusCache {
    @Volatile var permissionsNeedRefresh: Boolean = true
    @Volatile var notificationGranted: Boolean? = null
    @Volatile var batteryExempt: Boolean? = null

    // The DB-status text is also persisted to disk (Settings), so a cold app start - not just a tab
    // switch - can still paint instantly instead of showing nothing while Room re-queries.
    @Volatile private var memLastUpload: String? = null
    @Volatile private var memData: String? = null

    fun lastUpload(context: Context): String? =
        memLastUpload ?: Settings.get(context).statusCacheLastUpload.ifBlank { null }

    fun data(context: Context): String? =
        memData ?: Settings.get(context).statusCacheData.ifBlank { null }

    fun writeStatus(context: Context, lastUpload: String, data: String) {
        memLastUpload = lastUpload
        memData = data
        Settings.get(context).statusCacheLastUpload = lastUpload
        Settings.get(context).statusCacheData = data
    }
}

/** Status tab: permissions, sync status, data counts, and Sync now. */
class StatusFragment : Fragment(R.layout.fragment_status) {

    private lateinit var tvNotification: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvLastUpload: TextView
    private lateinit var tvData: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Warm cache from memory or disk: render it synchronously below instead of waiting on Room,
        // so switching back to this tab (or a cold app start after the previous exit) is instant.
        // Only hold the tab-switch transition (capped so a slow/stuck query can't hang it forever)
        // when there's truly nothing to show yet - the very first launch ever.
        val cachedLastUpload = StatusCache.lastUpload(requireContext())
        val cachedData = StatusCache.data(requireContext())
        if (cachedLastUpload == null) postponeEnterTransition(500, TimeUnit.MILLISECONDS)

        tvNotification = view.findViewById(R.id.tv_notification_status)
        tvBattery = view.findViewById(R.id.tv_battery_status)
        tvLastUpload = view.findViewById(R.id.tv_last_upload)
        tvData = view.findViewById(R.id.tv_data)
        if (cachedLastUpload != null && cachedData != null) {
            tvLastUpload.text = cachedLastUpload
            tvData.text = cachedData
        }
        applyCachedPermissionsIfAny()

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
        // Permissions can't change without leaving the app, so only actually re-check them on a real
        // app-level resume (MainActivity.onResume sets this) - a plain in-app tab switch to Status
        // just keeps showing the last-known state, applied already in onViewCreated.
        if (StatusCache.permissionsNeedRefresh) {
            refreshPermissions()
            StatusCache.permissionsNeedRefresh = false
        }
        refreshStatus()
        refreshHeartbeatBanner()
    }

    /** Paints the last-known permission state (if any) without re-checking anything - used when a
     *  tab switch recreates this Fragment but a real refresh isn't due yet. */
    private fun applyCachedPermissionsIfAny() {
        val granted = StatusCache.notificationGranted ?: return
        val exempt = StatusCache.batteryExempt ?: return
        tvNotification.text = getString(
            R.string.notification_access_status,
            if (granted) getString(R.string.granted) else getString(R.string.not_granted)
        )
        requireView().findViewById<View>(R.id.btn_grant_notification).visibility =
            if (granted) View.GONE else View.VISIBLE
        tvBattery.text = getString(
            R.string.battery_optimization_status,
            if (exempt) getString(R.string.exempt) else getString(R.string.not_exempt)
        )
        requireView().findViewById<View>(R.id.btn_ignore_battery).visibility =
            if (exempt) View.GONE else View.VISIBLE
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

        val tv = v.findViewById<TextView>(R.id.tv_heartbeat_status)
        val stateRes = when (Heartbeat.status(ctx)) {
            Heartbeat.Status.OK -> R.string.hb_state_ok
            Heartbeat.Status.DISABLED -> R.string.hb_state_disabled
            Heartbeat.Status.FAILED -> R.string.hb_state_failed
            Heartbeat.Status.UNPAIRED -> R.string.hb_state_unpaired
        }
        tv.text = getString(R.string.hb_status, getString(stateRes))
        val attr = if (Heartbeat.status(ctx) == Heartbeat.Status.FAILED)
            com.google.android.material.R.attr.colorError
        else com.google.android.material.R.attr.colorOnSurfaceVariant
        tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(tv, attr))
    }

    private fun refreshPermissions() {
        val granted = isNotificationAccessGranted()
        tvNotification.text = getString(
            R.string.notification_access_status,
            if (granted) getString(R.string.granted) else getString(R.string.not_granted)
        )
        // Only offer the action when it's actually needed (no flash: the button defaults to gone).
        requireView().findViewById<View>(R.id.btn_grant_notification).visibility =
            if (granted) View.GONE else View.VISIBLE

        val exempt = isBatteryOptimizationExempt()
        tvBattery.text = getString(
            R.string.battery_optimization_status,
            if (exempt) getString(R.string.exempt) else getString(R.string.not_exempt)
        )
        requireView().findViewById<View>(R.id.btn_ignore_battery).visibility =
            if (exempt) View.GONE else View.VISIBLE

        StatusCache.notificationGranted = granted
        StatusCache.batteryExempt = exempt
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
                val lastUploadText = getString(R.string.status_last_upload, lastStr, lastResult)
                val dataText = buildString {
                    appendLine(getString(R.string.status_total, total))
                    appendLine(getString(R.string.status_pending, pending))
                    append(getString(R.string.status_device_id, deviceId))
                }
                tvLastUpload.text = lastUploadText
                tvData.text = dataText
                StatusCache.writeStatus(ctx, lastUploadText, dataText)
                startPostponedEnterTransition()
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
