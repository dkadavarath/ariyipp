package com.noti.sender.status

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.noti.sender.config.SenderSettings
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.Diag
import com.noti.shared.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches battery level and wifi presence on the companion and pushes an alert to Main when either
 * crosses the thresholds Main configured. Unlike the readout stream this runs whether or not Main
 * is looking - an alert whose whole purpose is to reach someone who *isn't* watching can't be
 * gated on them watching.
 *
 * Lives for as long as [com.noti.sender.KeepAliveService] does, which is what keeps the process warm
 * enough for the callbacks to fire at all. [StatusWatchWorker] re-evaluates periodically as the
 * backstop for the case that matters most: an OEM killed the process while wifi was down, so no
 * callback will ever arrive to notice it came back - or never went away.
 *
 * Two rules the state machine turns on, both of them the difference between a useful alert and a
 * useless one:
 *  - An alert only counts as delivered if FCM actually accepted it. Losing wifi often means losing
 *    all connectivity, so the very alert about it is the one most likely to fail to send; marking
 *    it sent anyway would guarantee the user never hears about the outage they most needed to know
 *    about. A failed send leaves the state armed and the next evaluation retries.
 *  - Recovery is reported too (BATTERY_OK / WIFI_BACK), so Main can clear its warning by itself
 *    instead of leaving a stale "wifi down" sitting there after it came back hours ago.
 */
object DeviceMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises the two evaluation paths against each other so a battery broadcast arriving while
     *  a wifi alert is mid-send can't produce a duplicate. Deliberately *not* the class monitor that
     *  [start]/[stop] hold: an evaluation blocks on an FCM send for as long as the network takes,
     *  and service shutdown must never have to wait behind that. */
    private val evalLock = Any()

    private var batteryReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiTimer: Job? = null

    @Synchronized
    fun start(context: Context) {
        if (!com.noti.sender.CompanionRole.active(context)) return
        val app = context.applicationContext
        if (batteryReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    // Fires on every percent and on plug/unplug; the evaluation is a few prefs
                    // reads and one sticky-intent read, so there's no need to throttle it.
                    scope.launch { evaluateBattery(app) }
                }
            }
            try {
                app.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batteryReceiver = r
            } catch (e: Exception) {
                Diag.log("status monitor: battery watch unavailable (${e.message})")
            }
        }
        if (networkCallback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { evaluateWifi(app) }
                }

                override fun onLost(network: Network) {
                    scope.launch { evaluateWifi(app) }
                }
            }
            try {
                val cm = app.getSystemService(ConnectivityManager::class.java)
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm?.registerNetworkCallback(req, cb)
                networkCallback = cb
            } catch (e: Exception) {
                Diag.log("status monitor: wifi watch unavailable (${e.message})")
            }
        }
        // Baseline immediately: if wifi was already down before we started (a reboot with wifi off,
        // or an OEM kill mid-outage) no callback will ever arrive to tell us, and without this the
        // downtime clock would never start.
        scope.launch { evaluate(app) }
    }

    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        batteryReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        batteryReceiver = null
        networkCallback?.let {
            runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        wifiTimer?.cancel()
        wifiTimer = null
    }

    /** Re-check both conditions now. Safe to call from anywhere - it does its own network work on
     *  an IO scope and never blocks the caller. */
    fun evaluate(context: Context) {
        val app = context.applicationContext
        scope.launch {
            evaluateBattery(app)
            evaluateWifi(app)
        }
    }

    // ---- Battery ----

    private fun evaluateBattery(context: Context): Unit = synchronized(evalLock) {
        val s = SenderSettings.get(context)
        if (!s.statusSharingEnabled || !s.statusAlertsEnabled) return
        val battery = StatusCollector.readBattery(context)
        when (DeviceStatusPolicy.batteryDecision(battery.pct, battery.charging, s.statusBatteryPct, s.batteryAlertArmed)) {
            DeviceStatusPolicy.BatteryDecision.ALERT ->
                if (StatusReporter.send(context, WireMessage.StatusReport.BATTERY_LOW)) {
                    s.batteryAlertArmed = false
                    Diag.log("battery ${battery.pct}% is at/below ${s.statusBatteryPct}% - alerted main")
                }
            DeviceStatusPolicy.BatteryDecision.RECOVERED -> {
                // Re-arm first: even if the all-clear fails to send, the next low crossing must
                // still be able to alert.
                s.batteryAlertArmed = true
                StatusReporter.send(context, WireMessage.StatusReport.BATTERY_OK)
            }
            DeviceStatusPolicy.BatteryDecision.NONE -> Unit
        }
    }

    // ---- Wifi ----

    private fun evaluateWifi(context: Context): Unit = synchronized(evalLock) {
        val s = SenderSettings.get(context)
        if (!s.statusSharingEnabled || !s.statusAlertsEnabled) {
            wifiTimer?.cancel()
            wifiTimer = null
            return
        }
        val now = System.currentTimeMillis()
        if (StatusCollector.wifiConnected(context)) {
            wifiTimer?.cancel()
            wifiTimer = null
            val wasAlerted = s.wifiAlertSent
            s.wifiLostAtMs = 0L
            s.wifiAlertSent = false
            if (wasAlerted) {
                Diag.log("wifi is back - telling main")
                StatusReporter.send(context, WireMessage.StatusReport.WIFI_BACK)
            }
            return
        }

        if (s.wifiLostAtMs == 0L) {
            s.wifiLostAtMs = now
            Diag.log("wifi lost - alerting main in ${s.statusWifiLossMinutes} min if it doesn't return")
        }
        if (s.wifiAlertSent) return

        if (DeviceStatusPolicy.wifiAlertDue(s.wifiLostAtMs, now, s.statusWifiLossMinutes)) {
            if (StatusReporter.send(context, WireMessage.StatusReport.WIFI_LOST)) {
                s.wifiAlertSent = true
            } else {
                // Almost certainly because wifi was the only way out. Leave it unsent and try again
                // shortly - mobile data or wifi may be back by then.
                Diag.log("wifi-down alert could not be sent (no connectivity?) - will retry")
                armWifiTimer(context, RETRY_MS)
            }
            return
        }
        armWifiTimer(context, DeviceStatusPolicy.wifiAlertDelayMs(s.wifiLostAtMs, now, s.statusWifiLossMinutes))
    }

    /** How soon to retry an alert that couldn't be sent, well inside [StatusWatchWorker]'s cadence. */
    private const val RETRY_MS = 60_000L

    private fun armWifiTimer(context: Context, delayMs: Long): Unit = synchronized(evalLock) {
        wifiTimer?.cancel()
        wifiTimer = scope.launch {
            delay(delayMs)
            evaluateWifi(context)
        }
    }
}
