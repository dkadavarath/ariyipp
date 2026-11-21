package com.noti.logger.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.shared.Diag

/**
 * Schedules every reminder this module raises, and is the only place that decides *when* the OS
 * wakes us.
 *
 * Bills are delivered as high-priority notifications, scheduled with `setAndAllowWhileIdle` - no
 * exact-alarm permission, at the cost of firing inside a Doze maintenance window rather than to the
 * second. A 9am bill reminder does not care about several minutes. Parking does, which is why its
 * ring goes through [ParkingAlarm] and, in the IN_APP mode, uses an exact alarm.
 *
 * Every schedule is keyed by the row id of the thing being reminded about, used as the
 * PendingIntent request code. That is what makes a reminder addressable after the fact: extending a
 * parking session, or cancelling a bill you just marked paid, is a re-schedule or a cancel of one
 * specific id rather than a rebuild of everything.
 */
object ReminderScheduler {

    private const val ACTION_BILL = "com.noti.logger.action.REMINDER_BILL"
    private const val ACTION_PARKING = "com.noti.logger.action.REMINDER_PARKING"

    const val EXTRA_ID = "id"

    /** Request codes must not collide between the two kinds, so each gets its own space. */
    private const val BILL_BASE = 100_000
    private const val PARKING_BASE = 200_000

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    private fun pending(context: Context, action: String, base: Int, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            base + id.toInt(),
            Intent(context, ReminderAlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ---- Bills ----

    /** (Re)schedule one bill's reminder. A time already in the past is not scheduled; [rebuildAll]
     *  is what turns a missed-but-not-yet-overdue reminder into an immediate one. */
    fun scheduleBill(context: Context, billId: Long, atMs: Long) {
        if (atMs <= System.currentTimeMillis()) {
            cancelBill(context, billId)
            return
        }
        val am = alarmManager(context) ?: return
        try {
            // Inexact by design: no exact-alarm permission is asked of the user for bills.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(context, ACTION_BILL, BILL_BASE, billId))
        } catch (e: Exception) {
            Diag.log("reminder: could not schedule bill $billId (${e.message})")
        }
    }

    fun cancelBill(context: Context, billId: Long) {
        try {
            alarmManager(context)?.cancel(pending(context, ACTION_BILL, BILL_BASE, billId))
        } catch (e: Exception) {
            // nothing scheduled; cancelling is idempotent
        }
    }

    // ---- Parking (IN_APP backend) ----

    /** Exact where permitted, because a parking reminder that drifts past the expiry is worthless.
     *  Falls back to the inexact call rather than throwing when the permission isn't held. */
    fun scheduleParkingRing(context: Context, sessionId: Long, atMs: Long): Boolean {
        if (atMs <= System.currentTimeMillis()) return false
        val am = alarmManager(context) ?: return false
        val pi = pending(context, ACTION_PARKING, PARKING_BASE, sessionId)
        return try {
            if (ParkingAlarm.canScheduleExact(context)) {
                // setAlarmClock also puts the alarm icon in the status bar, which is the honest
                // signal for something that is going to ring.
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, null), pi)
            } else {
                Diag.log("parking alarm: no exact-alarm permission - falling back to an inexact wake")
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
            true
        } catch (e: Exception) {
            Diag.log("parking alarm: could not schedule session $sessionId (${e.message})")
            false
        }
    }

    fun cancelParkingRing(context: Context, sessionId: Long) {
        try {
            alarmManager(context)?.cancel(pending(context, ACTION_PARKING, PARKING_BASE, sessionId))
        } catch (e: Exception) {
            // idempotent
        }
    }

    // ---- Bulk (re)build ----

    /**
     * Rebuild every pending reminder from the database. Alarms do not survive a reboot or an app
     * reinstall, so this runs at boot and at app start; it is idempotent because every alarm is
     * keyed by its row id and simply replaces itself.
     *
     * Bills due on the same day are spaced apart here rather than at creation time, because
     * "how many bills land on that morning" is only knowable once they are all in the table.
     */
    fun rebuildAll(context: Context) {
        val settings = Settings.get(context)
        if (!settings.reminderEnabled) return
        val db = NotiDatabase.get(context)

        val bills = try { db.billOccurrenceDao().schedulable() } catch (e: Exception) { emptyList() }
        val wanted = bills.map {
            ReminderPolicy.billReminderAt(
                due = DueDates.fromMs(it.dueAt),
                // An entry may carry its own lead; -1 defers to the global setting.
                daysBefore = ReminderPolicy.effectiveLead(it.remindDaysBefore, settings.reminderDaysBefore),
                hour = settings.reminderHour,
                minute = settings.reminderMinute,
            )
        }
        val spaced = ReminderPolicy.spaceOut(wanted, settings.reminderSpacingMinutes)
        val now = System.currentTimeMillis()
        var catchUp = 0
        bills.forEachIndexed { i, bill ->
            val atMs = ReminderPolicy.toEpochMillis(spaced[i])
            // A reminder time that has already passed without a notification - the bill was found
            // inside its lead window, Doze held the alarm past 09:00 and this rebuild ran first, or
            // the phone was off - used to be cancelled, so that bill never reminded at all. If the
            // bill isn't overdue yet (due today or later), deliver it now instead, a few seconds
            // apart so a backlog doesn't land as one burst.
            val notYetOverdue = !DueDates.fromMs(bill.dueAt).isBefore(java.time.LocalDate.now())
            val fireAt = if (atMs <= now && notYetOverdue) now + 5_000L + 2_000L * catchUp++ else atMs
            if (bill.notified == 0) scheduleBill(context, bill.id, fireAt) else cancelBill(context, bill.id)
            try { db.billOccurrenceDao().setRemindAt(bill.id, atMs) } catch (e: Exception) { }
        }

        val sessions = try { db.parkingSessionDao().active() } catch (e: Exception) { emptyList() }
        for (session in sessions) {
            val at = ReminderPolicy.parkingFireAt(session.remindAt, session.expiresAt, System.currentTimeMillis(), session.rangFor) ?: continue
            ParkingAlarm.scheduleAt(context, session.id, session.name, session.zone, ReminderPolicy.fromEpochMillis(at))
            // A late ring is a one-off: record it now, since the clock-app route never reports back.
            if (at != session.remindAt) try { db.parkingSessionDao().markRang(session.id, session.remindAt) } catch (e: Exception) { }
        }
        Diag.log("reminders rebuilt: ${bills.size} bill(s), ${sessions.size} parking session(s)")
    }

    /** Drop everything, for when the module is switched off. */
    fun cancelAll(context: Context) {
        val db = NotiDatabase.get(context)
        try { db.billOccurrenceDao().schedulable().forEach { cancelBill(context, it.id) } } catch (e: Exception) { }
        try {
            db.parkingSessionDao().active().forEach {
                ParkingAlarm.cancel(context, it.id, it.name, it.zone)
                cancelParkingRing(context, it.id)
            }
        } catch (e: Exception) { }
    }

    internal fun isBill(action: String?) = action == ACTION_BILL
    internal fun isParking(action: String?) = action == ACTION_PARKING
}
