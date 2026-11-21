package com.noti.logger.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import com.noti.logger.config.Settings
import com.noti.shared.Diag
import java.time.LocalDateTime

/** Which mechanism rings a parking expiry. */
enum class ParkingAlarmMode {
    /** Hand the alarm to the phone's Clock app via [AlarmClock.ACTION_SET_ALARM]. */
    SYSTEM_CLOCK,

    /** Ring inside ariyipp with an exact alarm and a full-screen ringing screen. */
    IN_APP;

    companion object {
        fun of(name: String?): ParkingAlarmMode = try {
            valueOf(name ?: SYSTEM_CLOCK.name)
        } catch (e: IllegalArgumentException) {
            SYSTEM_CLOCK
        }
    }
}

/**
 * Rings a parking expiry, through whichever backend is selected.
 *
 * **Tested on a real device (Android 14), and the result decided the default.** The clock-app route
 * works perfectly while ariyipp is on screen: `ACTION_SET_ALARM` creates a labelled alarm, and
 * `ACTION_DISMISS_ALARM` searching by that label removes it again. From the background it does not
 * work at all - the platform logs `Background activity launch blocked ... (BAL_BLOCK)` and drops
 * the intent. Both calls are activity starts, so this applies to setting *and* cancelling.
 *
 * That is fatal for the actual use case. A parking ticket and its renewals arrive as notifications
 * while ariyipp is in your pocket, which is exactly the state in which the clock app cannot be
 * reached. So [ParkingAlarmMode.IN_APP] is the default, and SYSTEM_CLOCK is kept only as a
 * deliberate choice for someone who wants the alarm to live in their Clock app and accepts that it
 * is only set when the app happens to be open.
 *
 * Worse, the failure is undetectable after the fact: `startActivity` does not throw, returns
 * nothing, and completes normally when the OS drops it. The first version of this code reported
 * success for alarms that were never created. Hence [AppForeground] - the call is only attempted
 * when it can possibly work, and otherwise reports failure so [scheduleAt] can fall back to the
 * in-app alarm rather than leaving a session silently unarmed.
 *
 * The other structural limit stands regardless: `ACTION_SET_ALARM` carries an hour and a minute
 * with **no date field**, so an expiry more than [CLOCK_HORIZON_MS] out cannot be expressed and is
 * refused rather than set on the wrong day.
 *
 * IN_APP has none of these limits - exact date and time, settable and cancellable from the
 * background, extendable as often as a renewal arrives - at the cost of the exact-alarm permission,
 * and of ariyipp being the thing that rings.
  */
object ParkingAlarm {

    /** Alarms further out than this can't be expressed as a time-of-day for the clock app. */
    private const val CLOCK_HORIZON_MS = 23 * 60 * 60 * 1000L

    /** The label we set on a clock alarm, and search by to dismiss it. The id is what makes it
     *  addressable later - without it we could only dismiss "the next alarm", which might be the
     *  one you set to get up for work. */
    fun labelFor(sessionId: Long, name: String, zone: String): String {
        val where = if (zone.isBlank()) name else "$name - $zone"
        return "$where [ariyipp#$sessionId]"
    }

    /** Is a label one of ours, and for which session? */
    fun sessionIdOf(label: String?): Long? =
        Regex("""\[ariyipp#(\d+)]""").find(label.orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    fun mode(context: Context): ParkingAlarmMode = ParkingAlarmMode.of(Settings.get(context).parkingAlarmMode)

    /** Why the selected backend can't be used right now, or null when it's ready. Surfaced in the
     *  settings screen so a silently-broken alarm is visible before the parking expires, not after. */
    fun unavailableReason(context: Context): String? = when (mode(context)) {
        ParkingAlarmMode.SYSTEM_CLOCK ->
            if (clockAppAvailable(context)) null else "No clock app answers the set-alarm request on this phone."
        ParkingAlarmMode.IN_APP ->
            if (canScheduleExact(context)) null else "Alarms & reminders permission not granted."
    }

    fun clockAppAvailable(context: Context): Boolean = try {
        Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null
    } catch (e: Exception) {
        false
    }

    fun canScheduleExact(context: Context): Boolean = try {
        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am?.canScheduleExactAlarms() == true else true
    } catch (e: Exception) {
        false
    }

    /**
     * Ring at [at] for this session. Returns true if the alarm was handed off successfully - which
     * for SYSTEM_CLOCK means "we started the intent", not "the clock app definitely created it",
     * because the platform gives us no confirmation either way.
     */
    fun scheduleAt(context: Context, sessionId: Long, name: String, zone: String, at: LocalDateTime): Boolean {
        val whenMs = ReminderPolicy.toEpochMillis(at)
        if (mode(context) == ParkingAlarmMode.SYSTEM_CLOCK) {
            val reason = clockRouteUnusable(whenMs)
            if (reason == null) return setClockAlarm(context, labelFor(sessionId, name, zone), at)
            // Fall through to the in-app alarm rather than leaving the session unarmed. A parking
            // reminder that silently never fires is the worst outcome this feature has, so the
            // preference for the clock app yields to actually ringing.
            Diag.log("parking alarm: $reason - using the in-app alarm instead")
        }
        return ReminderScheduler.scheduleParkingRing(context, sessionId, whenMs)
    }

    /** Why the clock app can't be used for an alarm at [whenMs], or null when it can. */
    private fun clockRouteUnusable(whenMs: Long): String? = when {
        // Android 10+ drops an activity start from the background without telling us, so an attempt
        // from here would look like success and produce nothing.
        !AppForeground.isForeground() -> "app is in the background, so the clock app can't be reached"
        // ACTION_SET_ALARM has no date field; setting it would fire at the right time on the wrong day.
        whenMs - System.currentTimeMillis() > CLOCK_HORIZON_MS -> "expiry is >23h out and ACTION_SET_ALARM has no date field"
        else -> null
    }

    /**
     * Withdraw this session's alarm - a renewal moved it, or you ended the session.
     *
     * Both routes are cancelled regardless of the current mode, because a session armed under one
     * may be cancelled under the other: the mode is a setting the user can change mid-session, and
     * [scheduleAt] itself falls back to the in-app alarm when the clock app is unreachable.
     * Cancelling an alarm that was never set is a no-op either way.
     */
    fun cancel(context: Context, sessionId: Long, name: String, zone: String) {
        ReminderScheduler.cancelParkingRing(context, sessionId)
        if (mode(context) == ParkingAlarmMode.SYSTEM_CLOCK && AppForeground.isForeground()) {
            dismissClockAlarm(context, labelFor(sessionId, name, zone))
        }
    }

    // ---- System clock app ----

    private fun setClockAlarm(context: Context, label: String, at: LocalDateTime): Boolean = try {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, at.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            // Without this the clock app opens its editor and waits for a tap, which is useless for
            // an alarm set automatically when a parking notification lands.
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Diag.log("parking alarm → clock app: ${at.hour}:%02d \"$label\"".format(at.minute))
        true
    } catch (e: Exception) {
        // Most likely a background activity-start block (Android 10+) or no clock app at all.
        Diag.log("parking alarm → clock app FAILED: ${e.message}")
        false
    }

    private fun dismissClockAlarm(context: Context, label: String): Boolean = try {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Diag.log("parking alarm → clock app: dismiss \"$label\"")
        true
    } catch (e: Exception) {
        Diag.log("parking alarm → dismiss FAILED: ${e.message}")
        false
    }

    // ---- The try-it-and-see harness ----

    /** Label used by the settings screen's test buttons. Fixed, so Cancel test can always find it. */
    const val TEST_LABEL = "ariyipp test alarm [ariyipp#0]"

    /** Sets an alarm [minutes] from now through the selected backend, so the behaviour can be seen
     *  on the actual phone rather than reasoned about. */
    fun test(context: Context, minutes: Int = 2): Boolean {
        val at = LocalDateTime.now().plusMinutes(minutes.toLong())
        return when (mode(context)) {
            // The test button is pressed with the screen on, so this exercises the clock route in
            // the one state where it can work - which is the point: it shows whether *this* phone's
            // clock app honours the call at all, separately from the background restriction.
            ParkingAlarmMode.SYSTEM_CLOCK -> setClockAlarm(context, TEST_LABEL, at)
            ParkingAlarmMode.IN_APP -> ReminderScheduler.scheduleParkingRing(context, 0L, ReminderPolicy.toEpochMillis(at))
        }
    }

    fun cancelTest(context: Context): Boolean = when (mode(context)) {
        ParkingAlarmMode.SYSTEM_CLOCK -> dismissClockAlarm(context, TEST_LABEL)
        ParkingAlarmMode.IN_APP -> { ReminderScheduler.cancelParkingRing(context, 0L); true }
    }

    /** What the settings screen warns about for the clock backend: it works while you are looking
     *  at the app, and not otherwise. Stated as a fact of the platform, because it is one. */
    fun clockBackgroundCaveat(): String =
        "Android blocks this from the background, so an alarm is only handed to the Clock app while " +
            "ariyipp is open. Renewals that arrive in your pocket will use the in-app alarm instead."

    /**
     * Withdraw every clock-app alarm we are holding for a running session.
     *
     * Called when the user switches away from the clock backend, or turns the module off. Without
     * it those alarms are stranded: once the mode is no longer SYSTEM_CLOCK, [cancel] stops trying
     * to dismiss them, and nothing else ever will - so a phone would keep ringing at 2pm for a
     * parking session ariyipp is no longer tracking. Device testing produced exactly that: two
     * orphaned alarms left sitting in the Clock app.
     *
     * Only possible from the foreground, which is where both callers run.
     */
    fun dismissAllClockAlarms(context: Context) {
        // Read the foreground state on the caller's thread - both callers are UI, and by the time
        // the worker below runs we are still on screen.
        if (!AppForeground.isForeground()) return
        val app = context.applicationContext
        // Off the main thread: Room refuses a query there, and the first version of this swallowed
        // that refusal in its own catch and quietly withdrew nothing. The alarms then outlived the
        // setting that created them, which is the exact failure this method exists to prevent.
        Thread {
            val sessions = try {
                com.noti.logger.data.NotiDatabase.get(app).parkingSessionDao().active()
            } catch (e: Exception) {
                emptyList()
            }
            for (session in sessions) {
                dismissClockAlarm(app, labelFor(session.id, session.name, session.zone))
            }
            if (sessions.isNotEmpty()) Diag.log("withdrew ${sessions.size} clock alarm(s)")
        }.apply { name = "clock-alarm-withdraw" }.start()
    }

    /** Deep-link to the system screen that grants exact alarms, for the IN_APP backend. */
    fun exactAlarmSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

    /** Unused here, but kept next to the PendingIntent code it mirrors so the two stay in step. */
    internal fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
