package com.noti.logger.reminder

/**
 * What a rulebook entry is for. The two types share the rulebook, the scan and the tab, but almost
 * nothing else: a bill has a *date* and repeats forever, a parking session has a *time*, extends
 * itself while you keep renewing, and ends when you say it has.
 */
enum class RuleType {
    /** A recurring bill: match a statement, extract amount and due date, remind the day before. */
    BILL,

    /** A parking session: match a ticket, extract zone and expiry time, remind before it expires,
     *  and roll the reminder forward every time a renewal arrives. */
    PARKING,
}

/** Where a rule looks for its messages. */
enum class SourceType {
    /** Notifications from one captured app (see the app picker in notification logging). */
    NOTIFICATION,

    /** Relayed SMS from the companion. */
    SMS,

    /** Nothing is matched - the schedule alone drives it. Bills only. */
    MANUAL,
}

/** How a bill's due date is arrived at. */
enum class DueMode {
    /** Read it out of the message with the rule's date regex. */
    FROM_MESSAGE,

    /** The same day every period - "the 15th", whatever the statement says. */
    CONSTANT,

    /**
     * An anchor day plus a fixed number of days, which is how card statements actually work:
     * statement on the 10th, payment due 28 days later. For a matched rule the anchor is the
     * message's own date; for a manual rule it's [ReminderRule.anchorDay] of each period.
     */
    CALCULATED,
}

/**
 * How often a manual (unmatched) bill comes round. Paired with an interval count, so "every 28
 * days" and "every 3 weeks" are expressible rather than only "monthly" and "weekly" - real bills
 * land on cycles that no single unit describes.
 */
enum class Recurrence { DAILY, WEEKLY, MONTHLY, YEARLY, NEVER }

/** What happened to one occurrence of a bill. */
object BillStatus {
    /** Outstanding - this is what the tab highlights. */
    const val ACTIVE = 0

    /** Settled. Kept for history, and shown greyed when a rule has nothing active. */
    const val PAID = 1

    /** Deliberately passed over via "skip next" - paid early, or not owed this time. */
    const val SKIPPED = 2
}
