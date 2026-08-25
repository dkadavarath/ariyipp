package com.noti.logger.util

import android.content.Context
import android.text.format.DateUtils

/** Timestamp formatting for the chat list, bubbles, and day separators. */
object ChatTime {

    /** Short clock time, e.g. "10:24 AM". */
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

    /** The local calendar-day index for an instant - cheap (no Calendar allocation), so the
     *  per-row day checks below don't churn objects while binding a list. */
    private fun localDay(millis: Long): Long {
        val offset = java.util.TimeZone.getDefault().getOffset(millis)
        return Math.floorDiv(millis + offset, DateUtils.DAY_IN_MILLIS)
    }

    /** True when [a] and [b] fall on the same calendar day. */
    fun sameDay(a: Long, b: Long): Boolean = localDay(a) == localDay(b)

    private fun isToday(millis: Long) = localDay(millis) == localDay(System.currentTimeMillis())

    private fun isYesterday(millis: Long) = localDay(millis) == localDay(System.currentTimeMillis()) - 1

    private fun withinDays(millis: Long, days: Int) =
        System.currentTimeMillis() - millis < days * DateUtils.DAY_IN_MILLIS
}
