package com.noti.logger.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.shared.Diag
import java.time.format.DateTimeFormatter

/**
 * Where a scheduled reminder actually lands. Runs on the main thread with a ~10s budget, so it does
 * the database read on a goAsync() worker rather than blocking the broadcast.
 *
 * A reminder is re-read from the database at fire time rather than carried in the intent extras:
 * between scheduling and firing, the bill may have been marked paid or the parking session extended,
 * and the database is the only thing that knows. Firing from stale extras is how you get reminded
 * about a bill you settled last week.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0L)
        val action = intent.action
        val app = context.applicationContext
        if (!Settings.get(app).reminderEnabled) return

        val pending = goAsync()
        Thread {
            try {
                when {
                    ReminderScheduler.isBill(action) -> fireBill(app, id)
                    ReminderScheduler.isParking(action) -> fireParking(app, id)
                }
            } catch (e: Exception) {
                Diag.log("reminder alarm failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }.apply { name = "reminder-alarm" }.start()
    }

    private fun fireBill(context: Context, billId: Long) {
        val dao = NotiDatabase.get(context).billOccurrenceDao()
        val bill = dao.byId(billId) ?: return
        // Settled between scheduling and firing - say nothing.
        if (bill.status != BillStatus.ACTIVE) return

        val amountLine = ReminderFormat.amount(context, bill.amount, bill.currency, bill.amountRaw)
        val dueLine = if (bill.dueAt > 0) {
            ReminderFormat.dueLabel(context, bill.dueAt)
        } else {
            context.getString(com.noti.logger.R.string.rm_no_due_date)
        }
        ReminderNotifier.showBillDue(context, bill.id, bill.name, amountLine, dueLine)
        dao.markNotified(bill.id)
        Diag.log("reminder fired: bill \"${bill.name}\"")
    }

    private fun fireParking(context: Context, sessionId: Long) {
        val dao = NotiDatabase.get(context).parkingSessionDao()
        val session = dao.byId(sessionId) ?: return
        // Ended, or a renewal already pushed the expiry past this reminder - nothing to say.
        if (session.active != 1) return
        if (session.remindAt > System.currentTimeMillis() + 60_000L) return

        val whenLine = if (session.expiresAt > 0) {
            context.getString(
                com.noti.logger.R.string.rm_parking_expires_at,
                ReminderPolicy.fromEpochMillis(session.expiresAt).format(DateTimeFormatter.ofPattern("HH:mm")),
            )
        } else {
            context.getString(com.noti.logger.R.string.rm_no_expiry)
        }
        ReminderNotifier.showParkingExpiring(context, session.id, session.name, session.zone, whenLine)
        try { dao.markRang(session.id, session.remindAt) } catch (e: Exception) { }
        Diag.log("reminder fired: parking \"${session.name}\"")
    }
}
