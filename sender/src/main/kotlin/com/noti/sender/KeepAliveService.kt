package com.noti.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.noti.sender.config.SenderSettings

/**
 * A persistent foreground service whose only job is to keep ariy's process warm so the SMS receiver
 * fires and relays run, resisting Doze and OEM app-standup that would otherwise deep-sleep or
 * force-stop the app. Does no work itself - the missed-SMS sync is the backstop for anything that
 * still slips through.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // Open the app's launcher (this is a library - it can't name the app's Activity directly).
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setPackage(packageName)
        val open = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_STICKY // restart if the OS kills us
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Relay running", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Keeps SMS forwarding alive in the background" }
                )
            }
        }
    }

    companion object {
        private const val CHANNEL = "ariy_keepalive"
        private const val NOTIF_ID = 42

        /** Start the service if it's enabled and we can actually relay (RECEIVE_SMS granted). */
        fun ensureRunning(context: Context) {
            val s = SenderSettings.get(context)
            val canReceive = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
            if (s.keepAliveEnabled && canReceive) {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
