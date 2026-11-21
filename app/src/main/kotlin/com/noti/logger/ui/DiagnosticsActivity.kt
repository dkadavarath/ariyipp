package com.noti.logger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.push.CompanionStatus
import com.noti.logger.push.NotiStatusSender
import com.noti.shared.Diag
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything you look at when you want to know what the app is actually doing: the companion's
 * device readout, what notification capture has stored, and the activity log.
 *
 * The companion readout lives here rather than on Status because it is a diagnostic, not something
 * you open the app to see - and putting it here means the polling window is tied to this screen
 * being open, which is a tighter and more honest bound than "the Status tab is showing".
 */
class DiagnosticsActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_diagnostics
    override val titleRes = R.string.title_diagnostics

    /** Repaint cadence for the companion strip, independent of the companion's poll interval. */
    private val tickMs = 2_000L

    /** Icon alpha for a reading the companion couldn't take. Dimming says "not measured" where a
     *  zero-level gauge would wrongly say "empty". */
    private val unknownAlpha = 70
    private val fullAlpha = 255

    private lateinit var logView: TextView
    private var companionJob: Job? = null

    override fun onScreenCreated() {
        logView = findViewById(R.id.txt_diag)
        logView.movementMethod = ScrollingMovementMethod()
        findViewById<View>(R.id.btn_diag_clear).setOnClickListener { Diag.clear() }
        findViewById<View>(R.id.btn_diag_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("diagnostics", Diag.snapshot().joinToString("\n")))
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Diag.listener = { runOnUiThread { render() } }
        render()
        refreshCaptureCounts()
        startCompanionStatus()
    }

    override fun onPause() {
        super.onPause()
        Diag.listener = null
        stopCompanionStatus()
    }

    private fun render() {
        val lines = Diag.snapshot()
        logView.text = if (lines.isEmpty()) getString(R.string.diag_empty) else lines.joinToString("\n")
    }

    // ---- Notification capture ----

    private fun refreshCaptureCounts() {
        val ctx = applicationContext
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val dao = NotiDatabase.get(ctx).notificationDao()
                val s = Settings.get(ctx)
                listOf<Pair<CharSequence, CharSequence>>(
                    getString(R.string.status_total_label) to dao.totalCount().toString(),
                    getString(R.string.status_pending_label) to dao.pendingCount().toString(),
                    getString(R.string.status_device_id_label) to s.deviceId,
                )
            }
            com.noti.sender.ui.KeyValueList.fill(findViewById(R.id.tv_data), text)
        }
    }

    // ---- Companion device ----

    /**
     * Renews the companion's streaming window while this screen is open, and repaints as snapshots
     * land. Both stop in [onPause]: the readout is explicitly foreground-only, so leaving must cost
     * the companion nothing.
     */
    private fun startCompanionStatus() {
        val ctx = applicationContext
        val settings = Settings.get(ctx)
        val show = settings.role != Role.COMPANION &&
            settings.companionStatusEnabled && settings.peerPaired()
        findViewById<View>(R.id.card_companion_status).visibility =
            if (show) View.VISIBLE else View.GONE
        if (!show) return

        renderCompanionStatus()
        companionJob?.cancel()
        companionJob = lifecycleScope.launch {
            var sinceRenewMs = Long.MAX_VALUE // force a subscribe on the first pass
            while (true) {
                if (sinceRenewMs >= DeviceStatusPolicy.RENEW_SECONDS * 1000L) {
                    withContext(Dispatchers.IO) {
                        NotiStatusSender.requestStream(ctx, DeviceStatusPolicy.SUBSCRIPTION_SECONDS)
                    }
                    sinceRenewMs = 0L
                }
                renderCompanionStatus()
                delay(tickMs)
                sinceRenewMs += tickMs
            }
        }
    }

    private fun stopCompanionStatus() {
        companionJob?.cancel()
        companionJob = null
        val ctx = applicationContext
        val settings = Settings.get(ctx)
        if (settings.role == Role.COMPANION || !settings.companionStatusEnabled || !settings.peerPaired()) return
        // Cancel explicitly rather than letting the window run out, so the companion stops within a
        // second or two instead of streaming to a screen nobody is looking at.
        lifecycleScope.launch(Dispatchers.IO) { NotiStatusSender.requestStream(ctx, 0) }
    }

    private fun renderCompanionStatus() {
        val ctx = applicationContext
        val settings = Settings.get(ctx)
        val tiles = findViewById<View>(R.id.row_cs_tiles)
        val empty = findViewById<TextView>(R.id.tv_cs_readings)
        val flags = findViewById<TextView>(R.id.tv_cs_flags)

        val report = CompanionStatus.last(ctx)
        if (report == null) {
            // Nothing has arrived yet: the tiles would be a row of dashes, which reads as broken
            // rather than as pending.
            tiles.visibility = View.GONE
            flags.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        tiles.visibility = View.VISIBLE

        val dash = getString(R.string.cs_unknown)
        val showLabels = settings.companionStatusLabels
        fun label(id: Int, text: String) {
            findViewById<TextView>(id).apply {
                this.text = text
                visibility = if (showLabels) View.VISIBLE else View.GONE
            }
        }

        // Battery. The icon is a real gauge - setImageLevel drives a clip over the fill layer - so
        // the level stays legible even with the values switched off.
        val battery = findViewById<ImageView>(R.id.iv_cs_battery)
        val batteryPct = report.batteryPct
        battery.setImageLevel(if (batteryPct < 0) 0 else batteryPct.coerceIn(0, 100) * 100)
        battery.imageAlpha = if (batteryPct < 0) unknownAlpha else fullAlpha
        label(R.id.tv_cs_battery, if (batteryPct < 0) dash else "$batteryPct%")
        findViewById<View>(R.id.iv_cs_charging).visibility =
            if (report.charging) View.VISIBLE else View.GONE

        // Signal. Four bars, so each level is a quarter of the clip.
        val signal = findViewById<ImageView>(R.id.iv_cs_signal)
        val level = report.signalLevel
        signal.setImageLevel(if (level < 0) 0 else level.coerceIn(0, 4) * 2500)
        signal.imageAlpha = if (level < 0) unknownAlpha else fullAlpha
        label(
            R.id.tv_cs_signal,
            when {
                level < 0 -> dash
                report.networkType.isNotBlank() -> report.networkType
                else -> getString(R.string.cs_bars, level)
            },
        )

        // Network: which transport is actually carrying traffic.
        val network = findViewById<ImageView>(R.id.iv_cs_network)
        val networkIcon: Int
        val networkLabel: String
        when {
            report.wifi -> {
                networkIcon = R.drawable.ic_cs_wifi
                networkLabel = getString(R.string.cs_network_wifi)
            }
            report.mobileData -> {
                networkIcon = R.drawable.ic_cs_mobile
                networkLabel = getString(R.string.cs_network_mobile)
            }
            else -> {
                networkIcon = R.drawable.ic_cs_offline
                networkLabel = getString(R.string.cs_network_offline)
            }
        }
        network.setImageResource(networkIcon)
        label(R.id.tv_cs_network, networkLabel)

        findViewById<View>(R.id.tile_cs_vpn).visibility =
            if (report.vpn) View.VISIBLE else View.GONE

        // The occasional conditions, as one quiet line rather than three permanent ones. Words, not
        // icons, because they are rare enough that a glyph would need explaining.
        val active = buildList {
            if (report.powerSave) add(getString(R.string.cs_powersave))
            if (report.roaming) add(getString(R.string.cs_roaming))
            if (report.airplane) add(getString(R.string.cs_airplane))
        }
        flags.visibility = if (active.isEmpty() || !showLabels) View.GONE else View.VISIBLE
        flags.text = active.joinToString(getString(R.string.cs_flags_sep))

        // One missed update changes nothing on screen - the last known values stand while the retry
        // happens. Only once that retry has also failed is the card marked, and even then it stays
        // a mark rather than a claim the companion is gone: the heartbeat owns that.
        val suspect = DeviceStatusPolicy.readoutSuspect(
            CompanionStatus.lastAtMs(ctx), System.currentTimeMillis(), settings.companionPollSeconds,
        )
        val warning = findViewById<ImageView>(R.id.iv_cs_warning)
        warning.visibility = if (suspect) View.VISIBLE else View.GONE
        tiles.alpha = if (suspect) 0.6f else 1f
    }
}
