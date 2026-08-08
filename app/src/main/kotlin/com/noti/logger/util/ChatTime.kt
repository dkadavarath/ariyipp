package com.noti.logger.util

import android.content.Context
import android.text.format.DateUtils
import java.util.Calendar

/** Timestamp formatting for the chat list, bubbles, and day separators (Signal-like). */
object ChatTime {

    /** Short clock time, e.g. "10:24 AM" — used inside message bubbles. */
    fun clock(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)

    /** Conversation-list stamp: time if today, "Yesterday", weekday if this week, else a short date. */
    fun listStamp(context: Context, millis: Long): String = when {
        isToday(millis) -> clock(context, millis)
        isYesterday(millis) -> context.getString(com.noti.logger.R.string.date_yesterday)
        withinDays(millis, 7) -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    }

    /** Day-separator label between messages: "Today" / "Yesterday" / a full-ish date. */
    fun daySeparator(context: Context, millis: Long): String = when {
        isToday(millis) -> context.getString(com.noti.logger.R.string.date_today)
        isYesterday(millis) -> context.getString(com.noti.logger.R.string.date_yesterday)
        else -> DateUtils.formatDateTime(
            context, millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_MONTH
        )
    }

    /** True when [a] and [b] fall on the same calendar day. */
    fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun isToday(millis: Long) = sameDay(millis, System.currentTimeMillis())

    private fun isYesterday(millis: Long): Boolean {
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        return sameDay(millis, y)
    }

    private fun withinDays(millis: Long, days: Int) =
        System.currentTimeMillis() - millis < days * DateUtils.DAY_IN_MILLIS
}
