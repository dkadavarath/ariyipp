package com.noti.logger.reminder

import android.content.Context
import com.noti.logger.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Shared presentation of reminder values, so a notification and the tab never phrase the same
 *  bill two different ways. */
object ReminderFormat {

    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val shortFmt = DateTimeFormatter.ofPattern("d MMM")

    /** "6 Oct" this year, "6 Oct 2027" otherwise - the year only when it is news. */
    fun shortDate(dateMs: Long, today: LocalDate = LocalDate.now()): String {
        val d = ReminderPolicy.fromEpochMillis(dateMs).toLocalDate()
        return d.format(if (d.year == today.year) shortFmt else dateFmt)
    }

    /**
     * The amount line. Falls back to whatever the regex captured when the number couldn't be
     * parsed, because showing the raw text is what lets you spot a regex that grabbed the wrong
     * field - a blank line tells you nothing.
     */
    fun amount(context: Context, amount: Double?, currency: String, raw: String): String = when {
        amount != null -> {
            // Money is read by a person, so it formats in their locale - explicitly, because the
            // implicit default is the kind of thing that only misbehaves on someone else's phone.
            // Grouped: "18,402.75" reads at a glance where "18402.75" has to be counted.
            val n = if (amount % 1.0 == 0.0) String.format(Locale.getDefault(), "%,d", amount.toLong())
            else String.format(Locale.getDefault(), "%,.2f", amount)
            if (currency.isBlank()) n else "$currency $n"
        }
        raw.isNotBlank() -> raw
        else -> context.getString(R.string.rm_no_amount)
    }

    /** "Due tomorrow", "Due in 3 days", "Overdue by 2 days" - relative, because that is the thing
     *  you actually want to know at a glance. */
    fun dueLabel(context: Context, dueAtMs: Long, today: LocalDate = LocalDate.now()): String {
        val due = ReminderPolicy.fromEpochMillis(dueAtMs).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            days == 0L -> context.getString(R.string.rm_due_today)
            days == 1L -> context.getString(R.string.rm_due_tomorrow)
            days > 1L -> context.getString(R.string.rm_due_in_days, days.toInt(), due.format(dateFmt))
            days == -1L -> context.getString(R.string.rm_overdue_one)
            else -> context.getString(R.string.rm_overdue_days, (-days).toInt())
        }
    }

    /** [dueLabel] cut down for a one-line row: "Today", "Tomorrow", "6 Oct", "3d late". */
    fun dueShort(context: Context, dueAtMs: Long, today: LocalDate = LocalDate.now()): String {
        val due = ReminderPolicy.fromEpochMillis(dueAtMs).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            days == 0L -> context.getString(R.string.rm_short_today)
            days == 1L -> context.getString(R.string.rm_short_tomorrow)
            days > 1L -> shortDate(dueAtMs, today)
            else -> context.getString(R.string.rm_short_overdue, (-days).toInt())
        }
    }

    fun dateOnly(dueAtMs: Long): String =
        ReminderPolicy.fromEpochMillis(dueAtMs).toLocalDate().format(dateFmt)
}
