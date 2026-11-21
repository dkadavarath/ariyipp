package com.noti.logger.data

/**
 * How long relayed chat history is kept, and which arrivals are too old to notify about. Separate
 * from the notification-capture retention ([com.noti.logger.config.Settings.retentionDays]): that
 * one prunes captured notification rows, and once also truncated the chat - which nobody asked for.
 * Pure, so the rules are unit-testable.
 */
object ChatRetention {

    private const val DAY_MS = 86_400_000L

    /** A relayed SMS whose own timestamp is older than this on arrival is stored quietly: it is a
     *  backfill (a repush, a catch-up after an outage), not news, and a full-inbox repush must not
     *  raise one notification per message going back years. */
    const val NOTIFY_MAX_AGE_MS = 6 * 60 * 60_000L

    /** Rows older than the returned time are dropped; null means keep everything. [days] ≤ 0 is
     *  "forever" - the opposite of the notification knob, where 0 means keep nothing, because
     *  losing chat history by accident is far worse than keeping too much of it. */
    fun cutoff(days: Int, nowMs: Long): Long? = if (days <= 0) null else nowMs - days * DAY_MS

    /** True when a message timestamped [receivedAtMs] should be stored at all. */
    fun keeps(receivedAtMs: Long, days: Int, nowMs: Long): Boolean =
        cutoff(days, nowMs)?.let { receivedAtMs >= it } ?: true

    /** True when a message timestamped [receivedAtMs] is fresh enough to raise a notification. */
    fun notifies(receivedAtMs: Long, nowMs: Long): Boolean = nowMs - receivedAtMs <= NOTIFY_MAX_AGE_MS
}
