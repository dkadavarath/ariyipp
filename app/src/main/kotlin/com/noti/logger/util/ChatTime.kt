package com.noti.logger.util

import android.content.Context
import android.text.format.DateUtils
import java.util.Calendar

/** Timestamp formatting for the chat list, bubbles, and day separators (Signal-like). */
object ChatTime {

    /** Short clock time, e.g. "10:24 AM". */
    fun clock(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)

    /**
     * The centered separator shown above a cluster of messages, Google-Messages style: just the time
     * today, "Yesterday, 9:41 AM", "Mon, 9:41 AM" within the week, else "Jul 4, 9:41 AM".
     */
    fun clusterHeader(context: Context, millis: Long): String {
        val time = clock(context, millis)
        return when {
            isToday(millis) -> time
            isYesterday(millis) -> "${context.getString(com.noti.logger.R.string.date_yesterday)}, $time"
            withinDays(millis, 7) ->
                DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY) + ", " + time
            else -> {
                var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                if (!sameYear(millis, System.currentTimeMillis())) flags = flags or DateUtils.FORMAT_SHOW_YEAR
                DateUtils.formatDateTime(context, millis, flags) + ", " + time
            }
        }
    }

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

    private fun sameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
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
