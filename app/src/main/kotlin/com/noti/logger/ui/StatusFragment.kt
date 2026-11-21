package com.noti.logger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.push.Heartbeat
import com.noti.logger.util.Motion
import com.noti.sender.ui.StatusRow
import com.noti.sender.ui.StatusRow.Tone
import com.noti.sender.R as DesignR
import com.noti.logger.work.HeartbeatWorker
import com.noti.logger.work.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // The last sync (time and result) is also persisted to disk (Settings), so a cold app start -
    // not just a tab switch - can still paint instantly instead of showing nothing while it
    // re-reads. Stored as "<epoch ms>|<result>".
    @Volatile private var memLastUpload: String? = null

    fun lastUpload(context: Context): Pair<Long, String>? {
        val raw = memLastUpload ?: Settings.get(context).statusCacheLastUpload.ifBlank { null } ?: return null
        val at = raw.substringBefore('|').toLongOrNull() ?: return null // an older, preformatted text value
        return at to raw.substringAfter('|', "")
    }

    fun writeStatus(context: Context, atMs: Long, result: String) {
        val raw = "$atMs|$result"
        memLastUpload = raw
        Settings.get(context).statusCacheLastUpload = raw
    }
}

/** Status tab: permissions, sync status, data counts, and Sync now. */
class StatusFragment : Fragment(R.layout.fragment_status) {

    private lateinit var rowNotification: StatusRow
    private lateinit var rowBattery: StatusRow
    private lateinit var rowCompanion: StatusRow
    private lateinit var rowSync: StatusRow

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Warm cache from memory or disk: render it synchronously below instead of waiting on Room,
        // so switching back to this tab (or a cold app start after the previous exit) is instant.
        // Only hold the tab-switch transition (capped so a slow/stuck query can't hang it forever)
        // when there's truly nothing to show yet - the very first launch ever.
        val cachedLastUpload = StatusCache.lastUpload(requireContext())
        if (cachedLastUpload == null) postponeEnterTransition(500, TimeUnit.MILLISECONDS)

        rowNotification = StatusRow(view.findViewById(R.id.row_notification))
        rowBattery = StatusRow(view.findViewById(R.id.row_battery))
        rowCompanion = StatusRow(view.findViewById(R.id.row_companion))
        rowSync = StatusRow(view.findViewById(R.id.row_sync))
        if (cachedLastUpload != null) bindSync(cachedLastUpload.first, cachedLastUpload.second)
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
        // A stale clock is only a suspicion - check for real instead of just showing "Unreachable".
        // Any answer updates the clock; the next resume (or the verify run) reflects it.
        if (Heartbeat.isDisconnected(requireContext())) HeartbeatWorker.retryNow(requireContext())
    }

    /** Paints the last-known permission state (if any) without re-checking anything - used when a
     *  tab switch recreates this Fragment but a real refresh isn't due yet. */
    private fun applyCachedPermissionsIfAny() {
        val granted = StatusCache.notificationGranted ?: return
        val exempt = StatusCache.batteryExempt ?: return
        bindPermissions(granted, exempt)
    }

    private fun bindPermissions(granted: Boolean, exempt: Boolean) {
        val v = requireView()
        rowNotification.bind(
            DesignR.drawable.ic_bell,
            getString(R.string.st_notif_title),
            getString(if (granted) R.string.st_notif_ok_detail else R.string.st_notif_missing_detail),
            getString(if (granted) DesignR.string.st_allowed else DesignR.string.st_needs_access),
            if (granted) Tone.GOOD else Tone.WARN,
        )
        // Only offer the action when it's actually needed (no flash: the button defaults to gone).
        v.findViewById<View>(R.id.btn_grant_notification).visibility = if (granted) View.GONE else View.VISIBLE
        rowBattery.bind(
            DesignR.drawable.ic_battery,
            getString(DesignR.string.st_battery_title),
            getString(if (exempt) DesignR.string.st_battery_ok_detail else DesignR.string.st_battery_bad_detail),
            getString(if (exempt) DesignR.string.st_unrestricted else DesignR.string.st_restricted),
            if (exempt) Tone.GOOD else Tone.WARN,
        )
        v.findViewById<View>(R.id.btn_ignore_battery).visibility = if (exempt) View.GONE else View.VISIBLE
    }

    /** Webhook sync: when it last ran and whether that worked, in words - not "(ok)". */
    private fun bindSync(lastAt: Long, result: String) {
        val ctx = requireContext()
        val configured = Settings.get(ctx).webhookUrl.isNotBlank()
        val ok = result == "ok"
        when {
            !configured && lastAt == 0L -> rowSync.bind(
                com.noti.sender.R.drawable.ic_status_sync, getString(R.string.st_sync_title),
                getString(R.string.st_sync_off_detail), getString(DesignR.string.st_off), Tone.NEUTRAL,
            )
            lastAt == 0L -> rowSync.bind(
                com.noti.sender.R.drawable.ic_status_sync, getString(R.string.st_sync_title),
                getString(R.string.st_sync_never_detail), getString(R.string.st_never), Tone.NEUTRAL,
            )
            ok -> rowSync.bind(
                com.noti.sender.R.drawable.ic_status_sync, getString(R.string.st_sync_title),
                getString(R.string.st_last_synced, StatusRow.ago(ctx, lastAt)), getString(R.string.st_synced), Tone.GOOD,
            )
            else -> rowSync.bind(
                com.noti.sender.R.drawable.ic_status_sync, getString(R.string.st_sync_title),
                // The worker's own reason ("http 500 - alerted, retrying"), sentence-cased.
                getString(R.string.st_last_attempt, StatusRow.ago(ctx, lastAt), result.replaceFirstChar { it.uppercase() }),
                getString(R.string.st_retrying), Tone.BAD,
            )
        }
    }

    private fun refreshHeartbeatBanner() {
        val v = view ?: return
        val ctx = requireContext().applicationContext
        val show = Heartbeat.isDisconnected(ctx)
        // The banner pushes the cards below it; let them slide rather than jump.
        Motion.animateNextLayout(v as ViewGroup)
        v.findViewById<View>(R.id.card_hb_banner).visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            v.findViewById<TextView>(R.id.tv_hb_title).text =
                getString(R.string.hb_banner_title_peer, Heartbeat.peerLabel(ctx))
        }

        val lastBeat = Heartbeat.lastBeatAtMs(ctx)
        // "Companion last seen 2 min. ago": the row is the heartbeat, the detail names who answered.
        val peer = Heartbeat.peerLabel(ctx).replaceFirstChar { it.uppercase() }
        val seen = if (lastBeat > 0) getString(DesignR.string.st_last_seen, peer, StatusRow.ago(ctx, lastBeat))
            else getString(DesignR.string.st_never_seen, Heartbeat.peerLabel(ctx))
        val (badge, detail, tone) = when (Heartbeat.status(ctx)) {
            Heartbeat.Status.OK -> Triple(DesignR.string.st_connected, seen, Tone.GOOD)
            Heartbeat.Status.FAILED -> Triple(DesignR.string.st_unreachable, seen, Tone.BAD)
            Heartbeat.Status.DISABLED -> Triple(DesignR.string.st_off, getString(DesignR.string.st_hb_off_detail), Tone.NEUTRAL)
            Heartbeat.Status.UNPAIRED -> Triple(DesignR.string.st_not_paired, getString(DesignR.string.st_unpaired_detail), Tone.WARN)
        }
        rowCompanion.bind(com.noti.sender.R.drawable.ic_connection, getString(DesignR.string.st_heartbeat_title), detail, getString(badge), tone)
    }

    private fun refreshPermissions() {
        // Coming back from system Settings with access granted: the action button folds away.
        Motion.animateNextLayout(requireView() as ViewGroup)
        val granted = isNotificationAccessGranted()
        val exempt = isBatteryOptimizationExempt()
        bindPermissions(granted, exempt)

        StatusCache.notificationGranted = granted
        StatusCache.batteryExempt = exempt
    }

    private fun refreshStatus() {
        val ctx = requireContext().applicationContext
        val s = Settings.get(ctx)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val lastAt = s.lastUploadAtMs
            val lastResult = s.lastUploadResult.orEmpty()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                bindSync(lastAt, lastResult)
                StatusCache.writeStatus(ctx, lastAt, lastResult)
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
