package com.noti.logger.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.ui.MainActivity
import com.noti.shared.Diag
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Main's side of companion device status: keeps the latest snapshot, and turns the alert-triggered
 * ones into notifications.
 *
 * The snapshot is held both in memory and in [Settings]. The memory copy is what the Status card
 * reads on its repaint tick - the alternative, re-reading EncryptedSharedPreferences every couple
 * of seconds while the tab is open, means a Tink decrypt per tick for a value that was written by
 * this same process moments ago. The persisted copy exists only so a cold start paints the last
 * known reading immediately instead of an empty card.
 *
 * Recovery reports (BATTERY_OK / WIFI_BACK) cancel the corresponding warning rather than posting
 * one of their own. "Your companion's wifi came back" is not news worth a notification - but a
 * stale "wifi is down" left sitting there hours after it returned is actively misleading.
 */
object CompanionStatus {

    @Volatile private var cached: WireMessage.StatusReport? = null

    /** The latest snapshot, from memory if this process has one, else the persisted one. */
    fun last(context: Context): WireMessage.StatusReport? {
        cached?.let { return it }
        val encoded = Settings.get(context).lastCompanionStatus.ifBlank { return null }
        return try {
            (Wire.decode(encoded) as? WireMessage.StatusReport)?.also { cached = it }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * When the latest snapshot arrived, on this device's clock. 0 = never.
     *
     * Read straight from settings every time, unlike the report itself. Caching this one bought
     * almost nothing - it is a single Long, read twice a second at most while the Status tab is
     * open - and it cost correctness: a cached timestamp cannot be corrected by anything except
     * another push, so the stored value and the value in use could disagree with no way to
     * reconcile them. The report is a JSON string and a Tink decrypt, so that stays cached.
     */
    fun lastAtMs(context: Context): Long = Settings.get(context).lastCompanionStatusAtMs

    /** Has the readout missed enough updates to be worth flagging? One miss is absorbed silently;
     *  see [DeviceStatusPolicy.readoutSuspect]. */
    fun suspect(context: Context): Boolean =
        DeviceStatusPolicy.readoutSuspect(lastAtMs(context), System.currentTimeMillis(), Settings.get(context).companionPollSeconds)

    /** A snapshot arrived from the companion. */
    fun onReport(context: Context, report: WireMessage.StatusReport) {
        val settings = Settings.get(context)
        if (!settings.companionStatusEnabled) return

        val now = System.currentTimeMillis()
        cached = report
        settings.lastCompanionStatus = Wire.encode(report)
        settings.lastCompanionStatusAtMs = now

        when (report.trigger) {
            WireMessage.StatusReport.BATTERY_LOW -> alertBatteryLow(context, report)
            WireMessage.StatusReport.BATTERY_OK -> cancel(context, DeviceStatusPolicy.BATTERY_NOTIFICATION_ID)
            WireMessage.StatusReport.WIFI_LOST -> alertWifiLost(context, report)
            WireMessage.StatusReport.WIFI_BACK -> cancel(context, DeviceStatusPolicy.WIFI_NOTIFICATION_ID)
            else -> Unit // a poll: the card repaints from the cache, nothing to announce
        }
    }

    // ---- Notifications ----

    private const val CHANNEL_ID = "companion_status"

    private fun alertBatteryLow(context: Context, report: WireMessage.StatusReport) {
        val pct = report.batteryPct
        Diag.log("companion battery alert: $pct%")
        notify(
            context,
            DeviceStatusPolicy.BATTERY_NOTIFICATION_ID,
            context.getString(R.string.cs_alert_battery_title),
            context.getString(R.string.cs_alert_battery_body, pct, Settings.get(context).companionBatteryPct),
        )
    }

    private fun alertWifiLost(context: Context, report: WireMessage.StatusReport) {
        Diag.log("companion wifi alert: down ${report.wifiDownMinutes} min")
        // Say what it fell back to: "wifi is gone" reads very differently depending on whether the
        // companion still has a way to reach the internet at all.
        val fallback = context.getString(
            if (report.mobileData) R.string.cs_alert_wifi_on_mobile else R.string.cs_alert_wifi_offline
        )
        notify(
            context,
            DeviceStatusPolicy.WIFI_NOTIFICATION_ID,
            context.getString(R.string.cs_alert_wifi_title),
            context.getString(R.string.cs_alert_wifi_body, report.wifiDownMinutes.coerceAtLeast(1), fallback),
        )
    }

    private fun notify(context: Context, id: Int, title: String, body: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.cs_channel), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.cs_channel_desc) }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, note)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - the Status card still shows the condition.
        }
    }

    private fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }
}
