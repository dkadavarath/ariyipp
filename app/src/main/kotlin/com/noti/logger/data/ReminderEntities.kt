package com.noti.logger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A rulebook entry. One table for both rule types: they share the source/filter half exactly, and
 * splitting them would mean two editors, two DAOs and two scans to keep in step for the sake of a
 * few columns that sit null. [type] says which half of the columns is meaningful.
 */
@Entity(
    tableName = "reminder_rules",
    indices = [Index("enabled"), Index("sourcePackage")],
)
data class ReminderRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Shown on the reminder itself - this is the name you'll read at 9am, so it's the one field
     *  that is always required. */
    val name: String,

    /** [com.noti.logger.reminder.RuleType] name. */
    val type: String,

    /** [com.noti.logger.reminder.SourceType] name. */
    val source: String,

    /** Package to watch when source is NOTIFICATION. Blank for SMS/MANUAL. */
    val sourcePackage: String = "",

    /** Sender to match when source is SMS (substring, case-insensitive). Blank = any sender. */
    val sourceSender: String = "",

    val enabled: Int = 1,

    // ---- Filters (regex, one field per extracted value) ----

    /** Does this message belong to this rule? Blank = every message from the source. */
    val matchPattern: String = "",

    /** BILL: the amount. First capture group, else the whole match. */
    val amountPattern: String = "",

    /** BILL: the currency symbol or code. */
    val currencyPattern: String = "",

    /** BILL: the due date. PARKING: unused. */
    val datePattern: String = "",

    /** PARKING: the zone / bay / location name. */
    val zonePattern: String = "",

    /** PARKING: the expiry date-and-time (or just time). */
    val expiryPattern: String = "",

    /** java.time pattern for whichever of the two above needs parsing. Blank = try common shapes. */
    val dateFormat: String = "",

    // ---- Bill scheduling ----

    /** [com.noti.logger.reminder.DueMode] name. */
    val dueMode: String = "FROM_MESSAGE",

    /** DueMode.CONSTANT: the day of the month it's always due. */
    val constantDay: Int = 1,

    /** DueMode.CALCULATED: the anchor day (e.g. statement date), and days from it to the due date. */
    val anchorDay: Int = 1,
    val offsetDays: Int = 0,

    /** [com.noti.logger.reminder.Recurrence] name, for MANUAL bills. */
    val recurrence: String = "MONTHLY",

    /** How many units of [recurrence] between occurrences - "every 28 days", "every 3 weeks". */
    val recurrenceEvery: Int = 1,

    /**
     * The date the recurrence counts from, as epoch millis at local midnight. For a plain repeating
     * bill this is the first due date; for the calculated scheme it is the date the bill is raised,
     * with [offsetDays] added to reach the due date. 0 = not a date-anchored rule.
     */
    val anchorDate: Long = 0,

    /** Currency to show when nothing was captured (manual rules, or a rule with no currency regex). */
    val defaultCurrency: String = "",

    /** MANUAL rules: the amount each occurrence starts with. A message-driven rule reads its amount
     *  from the message instead, so this stays null there. */
    val defaultAmount: Double? = null,

    // ---- Payment resolver (optional): a message that marks this rule's bill paid ----

    /** 1 = watch for payment confirmations and settle the bill they belong to. */
    val payEnabled: Int = 0,

    /** [com.noti.logger.reminder.SourceType] name the payment message comes from - SMS or
     *  NOTIFICATION. Independent of the bill's own source: a manual rent bill can be settled by
     *  the bank's debit SMS, a card statement from one app by a payment receipt from another. */
    val paySource: String = "SMS",
    val paySender: String = "",
    val payPackage: String = "",

    /** Identifies a payment confirmation. Required - a blank one would treat every message from the
     *  source (the statement itself included) as a payment. */
    val payMatchPattern: String = "",

    /** The paid amount, for [payMatchAmount]. */
    val payAmountPattern: String = "",

    /** 1 = only settle a bill whose amount equals the paid amount; 0 = settle the newest bill that
     *  was already outstanding when the payment arrived. */
    val payMatchAmount: Int = 0,

    /** Free text kept with the entry - the account it comes off, a reference number, anything the
     *  name alone does not carry. */
    val notes: String = "",

    /** User-chosen grouping ("Streaming", "Utilities"), used to filter the list. Free text rather
     *  than a fixed set: what counts as a category is the user's business, not the app's. */
    val category: String = "",

    /** Days before the due date to remind, overriding the global setting. -1 = use the global. */
    val remindDaysBefore: Int = -1,

    /** Skip the next occurrence - the "I already paid this early" flag. Cleared once it's used, so
     *  it skips exactly one and the rule then carries on as normal. */
    val skipNext: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One instance of a bill: this month's card statement, this quarter's water bill. Rules produce
 * these; the tab lists them; the scheduler reminds about the active ones. History is just the rows
 * that are no longer ACTIVE, which is why nothing is ever deleted here on payment.
 */
@Entity(
    tableName = "reminder_bills",
    indices = [Index("ruleId"), Index("status"), Index("dueAt"), Index("sourceKey", unique = true)],
)
data class BillOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Owning rule, or 0 for a one-off added by hand. */
    val ruleId: Long = 0,

    /** Copied from the rule at creation so a renamed or deleted rule doesn't rewrite history. */
    val name: String,

    /** The date the bill is due, as epoch millis at that date's UTC midnight - a plain date that
     *  no time zone change can move (see [com.noti.logger.reminder.DueDates]). 0 = not captured. */
    val dueAt: Long = 0,

    val amount: Double? = null,
    val currency: String = "",

    /** Exactly what the regex captured, kept so a mis-parse can be seen and corrected rather than
     *  silently becoming a wrong number. */
    val amountRaw: String = "",
    val dueRaw: String = "",

    /** [com.noti.logger.reminder.BillStatus]. */
    val status: Int = 0,

    /** Copied from the rule when the occurrence is created, so editing a rule later never rewrites
     *  what a past bill said - history has to stay a record of what was true at the time. */
    val notes: String = "",
    val category: String = "",

    /** Days before the due date to remind. -1 = use the global setting. */
    val remindDaysBefore: Int = -1,

    /** Identity of the message this came from, so re-scanning the same statement doesn't create a
     *  second copy of the same bill. Blank for manual entries (which are never deduped). */
    val sourceKey: String = "",

    /** When the reminder is scheduled for, epoch millis. 0 = not scheduled. */
    val remindAt: Long = 0,

    /** Set once the reminder has actually fired, so a rescan doesn't re-notify for it. */
    val notified: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
    val settledAt: Long = 0,

    /** When the bill came into being: the statement message's own time for a scanned bill, the
     *  moment it was generated for a manual one. A payment only settles a bill issued before it -
     *  never one generated afterwards. 0 on rows from before v13 = fall back to [createdAt]. */
    val issuedAt: Long = 0,

    /** Identity of the payment message that settled this bill automatically ("" = none). One
     *  payment settles at most one bill, however often the scan sees it again. */
    val settleKey: String = "",

    /** 1 = marked paid by the payment resolver rather than by hand; shown in History. */
    val autoSettled: Int = 0,

    /** Which fields you corrected by hand ([EDITED_AMOUNT], [EDITED_DUE], [EDITED_CURRENCY],
     *  [EDITED_NAME]): a
     *  rescan of the statement never overwrites those, but may still fill in the rest. */
    val editedFields: Int = 0,
) {
    companion object {
        const val EDITED_AMOUNT = 1
        const val EDITED_DUE = 2
        const val EDITED_CURRENCY = 4
        const val EDITED_NAME = 8

        /** [editedFields] after saving [after] over [before] from the editor: every field that now
         *  differs joins the ones already marked. Pure. */
        fun editedMask(before: BillOccurrenceEntity, after: BillOccurrenceEntity): Int {
            var m = before.editedFields
            if (after.amount != before.amount) m = m or EDITED_AMOUNT
            if (after.dueAt != before.dueAt) m = m or EDITED_DUE
            if (after.currency != before.currency) m = m or EDITED_CURRENCY
            if (after.name != before.name) m = m or EDITED_NAME
            return m
        }

        /** What a rescan writes over [existing] given the freshly read [scanned]: the scan's values,
         *  except for the fields you edited, which keep yours. Pure. */
        fun mergeRescan(existing: BillOccurrenceEntity, scanned: BillOccurrenceEntity): BillOccurrenceEntity {
            val m = existing.editedFields
            fun has(bit: Int) = m and bit != 0
            return scanned.copy(
                amount = if (has(EDITED_AMOUNT)) existing.amount else scanned.amount,
                amountRaw = if (has(EDITED_AMOUNT)) existing.amountRaw else scanned.amountRaw,
                dueAt = if (has(EDITED_DUE)) existing.dueAt else scanned.dueAt,
                dueRaw = if (has(EDITED_DUE)) existing.dueRaw else scanned.dueRaw,
                currency = if (has(EDITED_CURRENCY)) existing.currency else scanned.currency,
                name = if (has(EDITED_NAME)) existing.name else scanned.name,
                editedFields = m,
            )
        }
    }
}

/**
 * A live parking session. Unlike a bill this is not a recurring occurrence - it is one continuous
 * thing that keeps moving its own expiry forward as renewals arrive, and ends only when you end it.
 * Keyed by zone so a renewal for the same zone extends the session rather than opening a second one.
 */
@Entity(
    tableName = "reminder_parking",
    indices = [Index("active"), Index("expiresAt"), Index("zone")],
)
data class ParkingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val ruleId: Long = 0,

    /** Rule name, for the reminder title. */
    val name: String,

    /** Captured zone/bay. Blank means it couldn't be read and needs your input. */
    val zone: String = "",

    /** Expiry, epoch millis. 0 means it couldn't be read and needs your input. */
    val expiresAt: Long = 0,

    /** When the reminder fires: expiry minus the lead time. */
    val remindAt: Long = 0,

    /** 1 while running. Only you end a session - an expiry passing doesn't, because an expired
     *  session you haven't dealt with is exactly the one you still need to see. */
    val active: Int = 1,

    /** Raised the "couldn't read zone/expiry" notification already, so it asks once, not on every
     *  renewal push from the same app. */
    val incompleteNotified: Int = 0,

    /** How many times a renewal has pushed the expiry out - shown in the tab so a session that has
     *  been running all day is recognisable as one. */
    val renewals: Int = 0,

    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0,

    /** The [remindAt] an alarm last rang for. A late alarm (reminder time already past, expiry not)
     *  rings once on arming; this stops every later rebuild from ringing it again. A renewal moves
     *  [remindAt], which arms the next one. */
    val rangFor: Long = 0,
)
