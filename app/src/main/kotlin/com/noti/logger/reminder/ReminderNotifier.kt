package com.noti.logger.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noti.logger.R
import com.noti.logger.ui.MainActivity
import com.noti.shared.Diag

/**
 * The three notification channels the Reminder module posts on, and the notifications themselves.
 *
 * Three separate channels rather than one, because they want different answers to "should this wake
 * me": a bill due tomorrow can wait for morning, a parking meter about to run out cannot, and "I
 * couldn't read the expiry time" is a request for input rather than an alert. Separate channels let
 * each be tuned - importance, sound, and crucially **Override Do Not Disturb** - independently, from
 * the system's own channel settings.
 *
 * On DND: an app cannot grant itself DND bypass. [setBypassDnd] only takes effect if the app holds
 * Notification Policy Access, which is a deliberate user grant. So the channels are created at high
 * importance with an alarm-category sound, and the settings screen deep-links straight to each
 * channel so the override can be flipped there. Channel settings are immutable once created - the
 * system ignores later changes by design - which is why the sound and importance have to be right
 * on the first post, and why the deep-link, not a code change, is how they get adjusted afterwards.
 */
object ReminderNotifier {

    /** A bill's due-date reminder. */
    const val CHANNEL_BILLS = "reminder_bills"

    /** A parking session about to expire - the one that most wants a DND override. */
    const val CHANNEL_PARKING = "reminder_parking"

    /** "I matched a message but couldn't read the zone or the time - please fill it in." */
    const val CHANNEL_ATTENTION = "reminder_attention"

    /** Notification ids are derived from row ids so each reminder replaces only itself. Each kind
     *  has a range of its own, above the relayed messages' (MessageNotifier: 100,000 up to about
     *  1.0 billion): with 1,000 apart, bill 1,001 took parking session 1's id and replaced it. */
    private const val BILL_BASE = 1_100_000_000
    private const val PARKING_BASE = 1_400_000_000
    private const val ATTENTION_BASE = 1_700_000_000
    private const val SPAN = 300_000_000L

    /** The id offset a reminder had before v1.46 (base + id), for one still showing from then;
     *  only below 1,000, where that scheme's ranges didn't run into each other. */
    private fun legacy(id: Long): Int? = id.takeIf { it in 0 until 1_000 }?.toInt()

    private fun billNid(id: Long) = BILL_BASE + (id % SPAN).toInt()
    private fun parkingNid(id: Long) = PARKING_BASE + (id % SPAN).toInt()
    private fun attentionNid(id: Long) = ATTENTION_BASE + (id % SPAN).toInt()

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return

        // Alarm-category audio: routed to the alarm stream, so it is audible when media and ring
        // are silenced - the closest a notification gets to behaving like an alarm.
        val alarmAudio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (mgr.getNotificationChannel(CHANNEL_BILLS) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_BILLS, context.getString(R.string.rm_channel_bills), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = context.getString(R.string.rm_channel_bills_desc)
                        enableVibration(true)
                        // Best-effort: only honoured with Notification Policy Access, which the
                        // settings screen explains and links to.
                        setBypassDnd(true)
                    }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_PARKING) == null) {
            mgr.createNotificationChannel(parkingChannel(context, alarmSound, alarmAudio))
        }
        if (mgr.getNotificationChannel(CHANNEL_ATTENTION) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.rm_channel_attention), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.rm_channel_attention_desc) }
            )
        }
    }

    /** Opens the Reminders tab. */
    private fun openTab(context: Context, extra: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_REMINDERS)
        if (extra != null) intent.putExtra(MainActivity.EXTRA_REMINDER_FOCUS, extra)
        return PendingIntent.getActivity(
            context, extra?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun showBillDue(context: Context, billId: Long, name: String, amountLine: String, dueLine: String) {
        ensureChannels(context)
        val body = listOf(amountLine, dueLine).filter { it.isNotBlank() }.joinToString(" · ")
        post(
            context, billNid(billId), CHANNEL_BILLS, name, body,
            openTab(context, "bill:$billId"), NotificationCompat.CATEGORY_REMINDER,
        )
    }

    /**
     * The parking alarm. Carries a **full-screen intent** to [ParkingAlarmActivity], which is what
     * turns this from a card that slides past into something that actually rings over the lock
     * screen. Android 14+ restricts full-screen intents to calling and alarm apps; when the
     * permission isn't held the platform quietly demotes it to a heads-up notification, which is
     * why the notification is still built to stand on its own.
     */
    fun showParkingExpiring(context: Context, sessionId: Long, name: String, zone: String, whenLine: String) {
        ensureChannels(context)
        val title = if (zone.isBlank()) name else context.getString(R.string.rm_parking_title, name, zone)
        val ring = PendingIntent.getActivity(
            context,
            sessionId.toInt(),
            Intent(context, ParkingAlarmActivity::class.java)
                .putExtra(ParkingAlarmActivity.EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, CHANNEL_PARKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(whenLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(whenLine))
            .setContentIntent(ring)
            .setFullScreenIntent(ring, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // Not auto-cancel and not dismissible by a swipe: an alarm you brushed off the lock
            // screen by accident is an alarm that didn't happen.
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(parkingNid(sessionId), note)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - the Reminders tab still shows the session.
        }
    }

    /** Whether the platform will honour our full-screen intent, or demote it to a heads-up card.
     *  Surfaced in settings so the difference isn't discovered at the meter. */
    fun canRingFullScreen(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else {
            true
        }

    /**
     * A parking message matched but a detail couldn't be read. This is a request for input, so it
     * deep-links to the session for editing - and it is raised once per session, not on every
     * renewal from the same app, which would turn a small gap in a regex into a stream of nagging.
     */
    fun showNeedsDetails(context: Context, sessionId: Long, name: String, missing: String) {
        ensureChannels(context)
        post(
            context, attentionNid(sessionId), CHANNEL_ATTENTION,
            context.getString(R.string.rm_attention_title, name),
            context.getString(R.string.rm_attention_body, missing),
            openTab(context, "parking:$sessionId"), NotificationCompat.CATEGORY_REMINDER,
        )
    }

    fun cancelBill(context: Context, billId: Long) {
        NotificationManagerCompat.from(context).cancel(billNid(billId))
        legacy(billId)?.let { NotificationManagerCompat.from(context).cancel(51_000 + it) }
    }

    fun cancelParking(context: Context, sessionId: Long) {
        NotificationManagerCompat.from(context).cancel(parkingNid(sessionId))
        NotificationManagerCompat.from(context).cancel(attentionNid(sessionId))
        legacy(sessionId)?.let {
            NotificationManagerCompat.from(context).cancel(52_000 + it)
            NotificationManagerCompat.from(context).cancel(53_000 + it)
        }
    }

    private fun post(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        body: String,
        intent: PendingIntent,
        category: String,
    ) {
        val note = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(category)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, note)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; the Reminders tab still shows everything.
        }
    }

    private fun parkingChannel(
        context: Context,
        sound: android.net.Uri?,
        audio: AudioAttributes,
    ): NotificationChannel =
        NotificationChannel(CHANNEL_PARKING, context.getString(R.string.rm_channel_parking), NotificationManager.IMPORTANCE_HIGH)
            .apply {
                description = context.getString(R.string.rm_channel_parking_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                setSound(sound, audio)
                // Only honoured when the app holds Do Not Disturb access at this moment - measured
                // on device: with access held the flag sticks, without it the platform forces it
                // off. Hence [enableParkingDndBypass], which rebuilds the channel once access is in
                // hand.
                setBypassDnd(true)
            }

    /**
     * Make the parking channel pierce Do Not Disturb, and report whether it now does.
     *
     * A channel's bypass flag is fixed when it is created and cannot be edited afterwards, so the
     * only way to turn it on for a channel that already exists is to delete and recreate it. That
     * resets the channel's other settings to ours, which is why this is never done automatically -
     * it runs only when the user asks for it by name.
     *
     * Returns false when Do Not Disturb access has not been granted, since without it the platform
     * silently forces the flag back off and recreating would achieve nothing but the reset.
     */
    fun enableParkingDndBypass(context: Context): Boolean {
        if (!hasDndAccess(context)) return false
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return false
        if (parkingBypassesDnd(context)) return true
        val alarmAudio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        mgr.deleteNotificationChannel(CHANNEL_PARKING)
        mgr.createNotificationChannel(parkingChannel(context, alarmSound, alarmAudio))
        val ok = parkingBypassesDnd(context)
        Diag.log(if (ok) "parking alarms now bypass Do Not Disturb" else "could not set the parking DND bypass")
        return ok
    }

    /**
     * Whether the user has given ariyipp Do Not Disturb access.
     *
     * This is what the [dndAccessIntent] screen grants, and the app only appears on that screen at
     * all because it declares ACCESS_NOTIFICATION_POLICY - without the manifest entry the screen
     * opens to a list that does not include us, which is exactly how this was found.
     */
    fun hasDndAccess(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    } catch (e: Exception) {
        false
    }

    /**
     * Whether the parking channel currently pierces Do Not Disturb.
     *
     * Worth checking separately from [hasDndAccess], because the two come apart: a channel's
     * bypass flag is fixed when the channel is first created, and the platform forces it off if the
     * app lacked DND access at that moment. Granting access afterwards does not retroactively
     * change it, and an app cannot rewrite the flag on a channel that already exists - by design,
     * so an app can't undo a user's choice. The only route from there is the user flipping
     * "Override Do Not Disturb" in the channel screen, which is why the settings UI sends them
     * there rather than claiming to have handled it.
     */
    fun parkingBypassesDnd(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_PARKING)?.canBypassDnd() == true
    } catch (e: Exception) {
        false
    }

    /** System settings for one of our channels, so the user can set Override Do Not Disturb there. */
    fun channelSettingsIntent(context: Context, channelId: String): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId)

    /** The Notification Policy Access screen - what actually enables a channel's DND override. */
    fun dndAccessIntent(): Intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}
