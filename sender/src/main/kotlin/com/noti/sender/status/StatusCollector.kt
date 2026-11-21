package com.noti.sender.status

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.noti.sender.config.SenderSettings
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.WireMessage

/**
 * Reads this device's current condition into a [WireMessage.StatusReport].
 *
 * Every reading is independently guarded: a snapshot with some fields unknown is far more useful
 * than no snapshot at all, and several of these APIs are exactly the kind that throw on one OEM,
 * return a redacted value without a permission, or simply don't exist below a given API level. So
 * nothing here throws - a reading that can't be taken keeps its "unknown" default and Main shows a
 * dash for it.
 *
 * Permissions: battery, connectivity, VPN and airplane state need none. Cellular signal and radio
 * type need READ_PHONE_STATE, which the companion already requests for SIM-slot selection - when it
 * isn't granted, those fields simply stay unknown rather than blocking the rest.
 */
object StatusCollector {

    fun snapshot(context: Context, trigger: Int = WireMessage.StatusReport.POLL): WireMessage.StatusReport {
        val now = System.currentTimeMillis()
        val battery = readBattery(context)
        val net = readNetwork(context)
        val cell = readCellular(context)
        return WireMessage.StatusReport(
            atMs = now,
            trigger = trigger,
            batteryPct = battery.pct,
            charging = battery.charging,
            powerSave = readPowerSave(context),
            signalLevel = cell.level,
            signalDbm = cell.dbm,
            networkType = cell.type,
            roaming = cell.roaming,
            wifi = net.wifi,
            wifiLevel = readWifiLevel(context),
            mobileData = net.cellular,
            online = net.validated,
            vpn = net.vpn,
            airplane = readAirplane(context),
            wifiDownMinutes = DeviceStatusPolicy.downMinutes(SenderSettings.get(context).wifiLostAtMs, now),
        )
    }

    // ---- Battery ----

    /** [pct] is 0-100, or -1 when unknown. */
    class Battery(val pct: Int, val charging: Boolean)

    /** Public because [DeviceMonitor] evaluates the low-battery threshold against exactly this
     *  reading - the alert and the readout must never disagree about what the level is. */
    fun readBattery(context: Context): Battery = try {
        // The sticky ACTION_BATTERY_CHANGED carries level and charge state in one read, with no
        // receiver to unregister - passing a null receiver just returns the last sticky value.
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        Battery(pct.coerceIn(-1, 100), charging)
    } catch (e: Exception) {
        Battery(-1, false)
    }

    private fun readPowerSave(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    } catch (e: Exception) {
        false
    }

    // ---- Connectivity ----

    private class Net(val wifi: Boolean, val cellular: Boolean, val vpn: Boolean, val validated: Boolean)

    /**
     * What the status bar would show: the transports of the *active* network, plus whether it has
     * actually validated. VPN is read separately across all networks, because when a VPN is up it
     * becomes the active network itself and would otherwise mask the underlying wifi/cellular
     * transport - which is precisely the thing the readout is meant to show.
     */
    // getAllNetworks() is deprecated in favour of a registered callback, but a callback can only
    // describe networks it was registered for and only from the moment it was registered - this is
    // a one-shot "what is true right now" read, taken on demand, which is exactly what the
    // deprecated call still does correctly.
    @Suppress("DEPRECATION")
    private fun readNetwork(context: Context): Net = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val vpn = anyVpn(cm)
        val underlying = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            // Behind a VPN, find the real carrier of the traffic among the other networks.
            cm.allNetworks.asSequence()
                .mapNotNull { cm.getNetworkCapabilities(it) }
                .firstOrNull {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
        } else {
            caps
        }
        Net(
            wifi = underlying?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cellular = underlying?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            vpn = vpn,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    } catch (e: Exception) {
        Net(wifi = false, cellular = false, vpn = false, validated = false)
    }

    @Suppress("DEPRECATION")
    private fun anyVpn(cm: ConnectivityManager?): Boolean = try {
        cm?.allNetworks?.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } == true
    } catch (e: Exception) {
        false
    }

    /** True iff a wifi network with internet is currently connected - the signal [DeviceMonitor]'s
     *  downtime clock is driven from, and the one place that definition lives. */
    @Suppress("DEPRECATION")
    fun wifiConnected(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.allNetworks?.any { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
    } catch (e: Exception) {
        // Unknown state: report "connected" so an unreadable ConnectivityManager can't manufacture
        // a wifi-loss alert out of nothing.
        true
    }

    /** Wifi bars 0-4, or -1 when the RSSI isn't readable (redacted without location on newer
     *  Android, or no wifi connected at all). */
    @Suppress("DEPRECATION")
    private fun readWifiLevel(context: Context): Int = try {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val rssi = wm?.connectionInfo?.rssi
        if (rssi == null || rssi == Int.MIN_VALUE || rssi >= 0 || rssi < -127) {
            -1
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0..getMaxSignalLevel(); normalise to the 0-4 the report documents.
            val max = wm.maxSignalLevel.coerceAtLeast(1)
            (wm.calculateSignalLevel(rssi) * 4) / max
        } else {
            WifiManager.calculateSignalLevel(rssi, 5)
        }
    } catch (e: Exception) {
        -1
    }

    private fun readAirplane(context: Context): Boolean = try {
        AndroidSettings.Global.getInt(context.contentResolver, AndroidSettings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (e: Exception) {
        false
    }

    // ---- Cellular ----

    private class Cell(val level: Int, val dbm: Int, val type: String, val roaming: Boolean)

    private fun readCellular(context: Context): Cell {
        val tm = try {
            context.getSystemService(TelephonyManager::class.java)
        } catch (e: Exception) {
            null
        } ?: return Cell(-1, 0, "", false)

        val roaming = try { tm.isNetworkRoaming } catch (e: Exception) { false }

        // Everything below is gated on READ_PHONE_STATE. Without it the platform either throws or
        // hands back a placeholder, so check first and leave the fields unknown rather than
        // reporting a confident zero.
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return Cell(-1, 0, "", roaming)

        var level = -1
        var dbm = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val ss = tm.signalStrength
                if (ss != null) {
                    level = ss.level.coerceIn(0, 4)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        dbm = ss.cellSignalStrengths.firstOrNull()?.dbm ?: 0
                        if (dbm == Int.MAX_VALUE || dbm == Int.MIN_VALUE) dbm = 0
                    }
                }
            } catch (e: Exception) {
                // leave unknown
            }
        }

        val type = try { networkTypeName(tm.dataNetworkType) } catch (e: Exception) { "" }
        return Cell(level, dbm, type, roaming)
    }

    /** The platform's radio-type constant as the name a user would recognise from a status bar. */
    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA -> "H"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE -> "E"
        TelephonyManager.NETWORK_TYPE_GPRS -> "G"
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD -> "CDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "WiFi calling"
        else -> ""
    }
}
