package com.noti.logger.reminder

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ReminderRuleEntity
import com.noti.shared.Diag
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Runs the rulebook over captured notifications and relayed SMS, and turns what it finds into bills
 * and parking sessions. Blocking - call from a worker or an IO scope.
 *
 * The scan is *incremental and idempotent*. It reads the window since the last successful scan (the
 * first ever run looks back [ReminderPolicy.FIRST_SCAN_LOOKBACK_DAYS]), and every bill it creates
 * carries a `sourceKey` derived from the message it came from, under a unique index. Re-running the
 * scan over the same messages therefore updates rather than duplicates - which matters, because
 * "Calculate now" exists precisely so it can be run again at any time, and because a message can be
 * seen twice when a scan window overlaps.
 *
 * Manual rules are not scanned at all; they are driven forward by [rollManualRules] from their own
 * recurrence, since there is no message to match.
 */
object ReminderScanner {

    class Result(val bills: Int, val parking: Int, val scanned: Int)

    /** Synchronized: scans now start on every eligible arrival as well as on schedule and from
     *  "Calculate now", and two at once would race each other's watermarks. */
    @Synchronized
    fun scan(context: Context, fullRescan: Boolean = false): Result {
        val settings = Settings.get(context)
        if (!settings.reminderEnabled) return Result(0, 0, 0)

        val db = NotiDatabase.get(context)
        val rules = try { db.reminderRuleDao().enabled() } catch (e: Exception) { emptyList() }
        if (rules.isEmpty()) {
            settings.lastReminderScanAtMs = System.currentTimeMillis()
            return Result(0, 0, 0)
        }

        val now = System.currentTimeMillis()
        val since = when {
            fullRescan -> now - ReminderPolicy.FIRST_SCAN_LOOKBACK_DAYS * 86_400_000L
            settings.lastReminderScanAtMs > 0L -> settings.lastReminderScanAtMs
            else -> now - ReminderPolicy.FIRST_SCAN_LOOKBACK_DAYS * 86_400_000L
        }

        // Counts, and whether a read failed: one that did must not advance the watermark, or that
        // window is skipped for good.
        val tally = Tally()

        // --- Notification-sourced rules. Read by row id up to a ceiling fixed now: per-package reads
        // happen one after another, and without the ceiling a row landing between two of them could
        // sit below the new watermark without either read having seen it.
        val notifDao = db.notificationDao()
        val lastNotifId = settings.lastReminderScanNotifId
        val notifCeiling = try { notifDao.maxId() ?: 0L } catch (e: Exception) { tally.readFailed = true; 0L }
        val lookbackFloor = now - ReminderPolicy.FIRST_SCAN_LOOKBACK_DAYS * 86_400_000L
        val readNotifs: (String) -> List<com.noti.logger.data.NotificationEntity> = { pkg ->
            if (fullRescan || lastNotifId < 0) notifDao.forPackageSince(pkg, since).filter { it.id <= notifCeiling }
            else notifDao.forPackageInIds(pkg, lastNotifId, notifCeiling, lookbackFloor)
        }
        scanNotifications(context, rules, readNotifs, tally)

        // --- SMS-sourced rules, from the relayed chat history. Payment watchers are read from the
        // same rows, so an SMS-settled rule needs them fetched even when no rule takes its *bill*
        // from SMS (a manual rent bill paid by bank debit SMS).
        val payRules = payRulesOf(rules)
        val (smsRows, maxSmsId) = scanSms(context, settings, rules, Window(fullRescan, since, now), tally)

        // --- Payments, after every bill is in: a statement and its payment can land in one window,
        // and the payment must find the bill it settles. Then manual rules roll on past what was paid.
        val (paid, payReadFailed) = payPass(context, payRules, smsRows, readNotifs)
        if (payReadFailed) tally.readFailed = true

        rollManualRules(context)

        // Stamp the window end from before the scan started, not from now: a message that arrived
        // while the scan was running would otherwise fall in the gap between the two and never be
        // looked at by either run.
        if (tally.readFailed) {
            Diag.log("reminder scan: a read failed - window kept, will rescan it next run")
        } else {
            settings.lastReminderScanAtMs = now
            // Only ever forward; with no SMS rules (or no new rows) the old mark stands.
            if (maxSmsId > settings.lastReminderScanSmsId) settings.lastReminderScanSmsId = maxSmsId
            if (notifCeiling > settings.lastReminderScanNotifId) settings.lastReminderScanNotifId = notifCeiling
        }
        ReminderScheduler.rebuildAll(context)
        Diag.log("reminder scan: ${tally.scanned} message(s) → ${tally.bills} bill(s), ${tally.parking} parking event(s), $paid paid automatically")
        return Result(tally.bills, tally.parking, tally.scanned)
    }

    private enum class Applied { NONE, BILL, PARKING }

    /** What one scan found, counted as it goes. */
    private class Tally {
        var bills = 0
        var parking = 0
        var scanned = 0
        var readFailed = false

        fun count(applied: Applied) {
            when (applied) {
                Applied.BILL -> bills++
                Applied.PARKING -> parking++
                Applied.NONE -> Unit
            }
        }
    }

    /** Notification-sourced rules, grouped by package so the table is read once per package. */
    private fun scanNotifications(
        context: Context,
        rules: List<ReminderRuleEntity>,
        readNotifs: (String) -> List<com.noti.logger.data.NotificationEntity>,
        tally: Tally,
    ) {
        val byPackage = rules.filter { SourceType.valueOf(it.source) == SourceType.NOTIFICATION }
            .groupBy { it.sourcePackage }
        for ((pkg, pkgRules) in byPackage) {
            if (pkg.isBlank()) continue
            val rows = try {
                readNotifs(pkg)
            } catch (e: Exception) {
                tally.readFailed = true
                emptyList()
            }
            for (row in rows) {
                tally.scanned++
                val text = ReminderExtractor.notificationText(row.title, row.text, row.bigText, row.subText)
                if (text.isBlank()) continue
                for (rule in pkgRules) tally.count(apply(context, rule, text, row.postTime, "n:${row.uid}"))
            }
        }
    }

    /** Which rows a scan reads: a full rescan of the lookback, or what came since [since]. */
    private class Window(val fullRescan: Boolean, val since: Long, val now: Long)

    /** Rules that settle their bills from payment messages. */
    private fun payRulesOf(rules: List<ReminderRuleEntity>) = rules.filter {
        it.payEnabled == 1 && it.payMatchPattern.isNotBlank() && RuleType.valueOf(it.type) == RuleType.BILL
    }

    /** SMS-sourced rules over the relayed chat history. Returns the rows read (the payment pass
     *  reads them too) and the highest row id among them (-1 if none). */
    private fun scanSms(
        context: Context,
        settings: Settings,
        rules: List<ReminderRuleEntity>,
        window: Window,
        tally: Tally,
    ): Pair<List<com.noti.logger.data.RelayedMessageEntity>, Long> {
        val smsRules = rules.filter { SourceType.valueOf(it.source) == SourceType.SMS }
        val paySmsRules = payRulesOf(rules).filter { it.paySource == SourceType.SMS.name }
        if (smsRules.isEmpty() && paySmsRules.isEmpty()) return emptyList<com.noti.logger.data.RelayedMessageEntity>() to -1L
        val lastSmsId = settings.lastReminderScanSmsId
        val rows = try {
            val dao = NotiDatabase.get(context).relayedMessageDao()
            if (window.fullRescan || lastSmsId < 0) dao.receivedSince(window.since)
            // Late arrivals up to the first-scan lookback are still caught; a backfilled
            // message older than that is history, not a bill to chase.
            else dao.receivedAfterId(lastSmsId, window.now - ReminderPolicy.FIRST_SCAN_LOOKBACK_DAYS * 86_400_000L)
        } catch (e: Exception) {
            tally.readFailed = true
            emptyList()
        }
        for (row in rows) {
            tally.scanned++
            if (row.body.isBlank()) continue
            for (rule in smsRules) {
                // The sender filter is a plain substring, not a regex: it is matched against a
                // phone number or short code, where regex buys nothing and costs clarity.
                if (rule.sourceSender.isNotBlank() && !row.sender.contains(rule.sourceSender, ignoreCase = true)) continue
                tally.count(apply(context, rule, "${row.sender}\n${row.body}", row.receivedAt, "s:${row.id}"))
            }
        }
        return rows to (rows.maxOfOrNull { it.id } ?: -1L)
    }

    private fun apply(context: Context, rule: ReminderRuleEntity, text: String, atMs: Long, msgKey: String): Applied =
        when (RuleType.valueOf(rule.type)) {
            RuleType.BILL -> if (applyBill(context, rule, text, atMs, msgKey)) Applied.BILL else Applied.NONE
            RuleType.PARKING -> if (applyParking(context, rule, text, atMs)) Applied.PARKING else Applied.NONE
        }

    // ---- Bills ----

    private fun applyBill(context: Context, rule: ReminderRuleEntity, text: String, atMs: Long, msgKey: String): Boolean {
        val messageDate = ReminderPolicy.fromEpochMillis(atMs).toLocalDate()
        val match = ReminderExtractor.extractBill(
            text = text,
            matchPattern = rule.matchPattern,
            amountPattern = rule.amountPattern,
            currencyPattern = rule.currencyPattern,
            datePattern = rule.datePattern,
            dateFormat = rule.dateFormat,
            today = messageDate,
        ) ?: return false

        val due = ReminderPolicy.dueDate(
            mode = DueMode.valueOf(rule.dueMode),
            anchorDate = messageDate,
            constantDay = rule.constantDay,
            anchorDay = rule.anchorDay,
            offsetDays = rule.offsetDays,
            extracted = match.dueDate,
        )

        val dao = NotiDatabase.get(context).billOccurrenceDao()
        val messageKey = key("${rule.id}|$msgKey")
        // The same message again, or - a bank re-sending the reminder - another message about the
        // same bill: same rule, same due date and the same amount (see sameCycle), or a message
        // that states the amount on file next to the one the rule read (a reminder leading with the
        // minimum due, then the total). Either way it's the bill already on file, not a second one
        // that doubled the total. Two cards from one bank both due on the 5th differ in amount, and
        // neither message names the other's, so they stay two bills.
        var byMention = false
        val existing = try {
            dao.bySourceKey(messageKey) ?: due?.let { d ->
                val sameDay = dao.forRuleAndDue(rule.id, midnightMs(d))
                sameDay.firstOrNull { sameCycle(it.amount, match.amount) }
                    ?: sameDay.firstOrNull { mentionsAmount(text, it.amount) }?.also { byMention = true }
            }
        } catch (e: Exception) { null }
        val sourceKey = existing?.sourceKey ?: messageKey

        // "Skip next" consumes itself here: the occurrence is still recorded, so history stays
        // honest about the statement having arrived, but it starts settled and never reminds.
        val skip = existing == null && rule.skipNext == 1 &&
            try { NotiDatabase.get(context).reminderRuleDao().claimSkipNext(rule.id) == 1 } catch (e: Exception) { false }

        val row = BillOccurrenceEntity(
            id = existing?.id ?: 0,
            ruleId = rule.id,
            name = rule.name,
            dueAt = due?.let { midnightMs(it) } ?: 0L,
            // Found by its amount being mentioned: the message also gives another figure, and the
            // one on file (read first) stays the bill's amount.
            amount = if (byMention) existing?.amount else match.amount,
            currency = match.currency?.ifBlank { null } ?: rule.defaultCurrency,
            amountRaw = if (byMention) existing?.amountRaw.orEmpty() else match.amountRaw.orEmpty(),
            dueRaw = match.dueDateRaw.orEmpty(),
            notes = existing?.notes ?: rule.notes,
            category = existing?.category ?: rule.category,
            remindDaysBefore = existing?.remindDaysBefore ?: rule.remindDaysBefore,
            status = existing?.status ?: if (skip) BillStatus.SKIPPED else BillStatus.ACTIVE,
            sourceKey = sourceKey,
            remindAt = existing?.remindAt ?: 0L,
            notified = existing?.notified ?: 0,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            settledAt = existing?.settledAt ?: if (skip) System.currentTimeMillis() else 0L,
            // The statement's own time: a payment only settles a bill issued before it.
            issuedAt = existing?.issuedAt?.takeIf { it > 0 } ?: atMs,
            settleKey = existing?.settleKey ?: "",
            autoSettled = existing?.autoSettled ?: 0,
        )
        return try {
            if (existing == null) {
                dao.insert(row) > 0
            } else {
                // A restated statement (banks re-send) can correct an amount or a date; keeping the
                // user's status is the point - re-reading a message must never un-pay a bill. Nor
                // may it undo a correction you made by hand (see BillOccurrenceEntity.editedFields).
                val merged = BillOccurrenceEntity.mergeRescan(existing, row)
                if (merged == existing) return false
                dao.update(merged)
                false // updated, not new: don't count it as a fresh find
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Manual recurring rules have no message to match, so each run tops them up: if the rule has no
     * outstanding occurrence, create the next one from its recurrence. That way a monthly rule
     * produces exactly one active bill at a time, and marking it paid is what lets the next appear.
     */
    fun rollManualRules(context: Context) {
        val db = NotiDatabase.get(context)
        val rules = try { db.reminderRuleDao().enabled() } catch (e: Exception) { return }
        val today = LocalDate.now()
        for (rule in rules) {
            if (RuleType.valueOf(rule.type) != RuleType.BILL) continue
            if (SourceType.valueOf(rule.source) != SourceType.MANUAL) continue
            val dao = db.billOccurrenceDao()
            val outstanding = try { dao.activeCountForRule(rule.id) } catch (e: Exception) { 1 }
            if (outstanding > 0) continue

            val last = try { dao.lastSettledForRule(rule.id) } catch (e: Exception) { null }
            val recurrence = Recurrence.valueOf(rule.recurrence)
            val lastDue = last?.dueAt?.takeIf { it > 0 }?.let { DueDates.fromMs(it) }

            // Anchored rules carry their own start date; older rules (and any created before v10)
            // fall back to the day-of-month fields they were built with.
            val anchorStart = if (rule.anchorDate > 0) {
                DueDates.fromMs(rule.anchorDate)
            } else {
                when (DueMode.valueOf(rule.dueMode)) {
                    DueMode.CALCULATED -> ReminderPolicy.nextDayOfMonth(rule.anchorDay, today)
                    else -> ReminderPolicy.nextDayOfMonth(rule.constantDay, today)
                }
            }
            val offset = if (DueMode.valueOf(rule.dueMode) == DueMode.CALCULATED) rule.offsetDays else 0
            val due = ReminderPolicy.nextManualDue(
                anchorStart = anchorStart,
                recurrence = recurrence,
                every = rule.recurrenceEvery,
                offsetDays = offset,
                after = lastDue,
                today = today,
            ) ?: continue

            val skip = rule.skipNext == 1 &&
                try { db.reminderRuleDao().claimSkipNext(rule.id) == 1 } catch (e: Exception) { false }
            try {
                dao.insert(
                    BillOccurrenceEntity(
                        ruleId = rule.id,
                        name = rule.name,
                        dueAt = midnightMs(due),
                        amount = rule.defaultAmount,
                        currency = rule.defaultCurrency,
                        notes = rule.notes,
                        category = rule.category,
                        remindDaysBefore = rule.remindDaysBefore,
                        status = if (skip) BillStatus.SKIPPED else BillStatus.ACTIVE,
                        // Manual occurrences are never deduped against a message; a per-rule-and-date
                        // key still stops a double run from creating the same month twice.
                        sourceKey = key("manual|${rule.id}|$due"),
                        settledAt = if (skip) System.currentTimeMillis() else 0L,
                        // Generated now - so only a payment made from here on can settle it.
                        issuedAt = System.currentTimeMillis(),
                    )
                )
            } catch (e: Exception) {
                // unique-key collision: this occurrence already exists, which is the desired outcome
            }
        }
    }

    // ---- Payments ----

    /** The payment pass over one window: notification payments read per package, SMS payments
     *  from [smsRows]. Returns how many bills were settled, and whether a read failed. */
    private fun payPass(
        context: Context,
        payRules: List<ReminderRuleEntity>,
        smsRows: List<com.noti.logger.data.RelayedMessageEntity>,
        readNotifs: (String) -> List<com.noti.logger.data.NotificationEntity>,
    ): Pair<Int, Boolean> {
        val db = NotiDatabase.get(context)
        var paid = 0
        var readFailed = false
        for ((pkg, pkgRules) in payRules.filter { it.paySource == SourceType.NOTIFICATION.name && it.payPackage.isNotBlank() }
            .groupBy { it.payPackage }) {
            val rows = try { readNotifs(pkg) } catch (e: Exception) { readFailed = true; emptyList() }
            for (row in rows.sortedBy { it.postTime }) {
                val text = ReminderExtractor.notificationText(row.title, row.text, row.bigText, row.subText)
                if (text.isBlank()) continue
                for (rule in pkgRules) {
                    val isStatement = rule.source == SourceType.NOTIFICATION.name && rule.sourcePackage == pkg
                    if (applyPayment(context, rule, text, row.postTime, "n:${row.uid}", isStatement)) paid++
                }
            }
        }
        val paySmsRules = payRules.filter { it.paySource == SourceType.SMS.name }
        for (row in smsRows.sortedBy { it.receivedAt }) {
            if (row.body.isBlank()) continue
            for (rule in paySmsRules) {
                if (rule.paySender.isNotBlank() && !row.sender.contains(rule.paySender, ignoreCase = true)) continue
                val isStatement = rule.source == SourceType.SMS.name &&
                    (rule.sourceSender.isBlank() || row.sender.contains(rule.sourceSender, ignoreCase = true))
                if (applyPayment(context, rule, "${row.sender}\n${row.body}", row.receivedAt, "s:${row.id}", isStatement)) paid++
            }
        }
        return paid to readFailed
    }

    /**
     * Runs the payment pass for one rule over the lookback window, outside the normal scan. Needed
     * whenever a rule's payment side is switched on or changed: the confirmation it was set up from
     * has usually been scanned already (the scan only reads what's new), so without this the bill it
     * pays would never be marked. Idempotent - a payment already used settles nothing twice.
     */
    @Synchronized
    fun resolvePaymentsFor(context: Context, ruleId: Long): Int {
        val db = NotiDatabase.get(context)
        val rule = try { db.reminderRuleDao().byId(ruleId) } catch (e: Exception) { null } ?: return 0
        if (rule.payEnabled != 1 || rule.payMatchPattern.isBlank() || RuleType.valueOf(rule.type) != RuleType.BILL) return 0
        val since = System.currentTimeMillis() - ReminderPolicy.FIRST_SCAN_LOOKBACK_DAYS * 86_400_000L
        val sms = if (rule.paySource == SourceType.SMS.name) {
            try { db.relayedMessageDao().receivedSince(since) } catch (e: Exception) { emptyList() }
        } else emptyList()
        val (paid, _) = payPass(context, listOf(rule), sms) { pkg -> db.notificationDao().forPackageSince(pkg, since) }
        if (paid > 0) {
            rollManualRules(context)
            ReminderScheduler.rebuildAll(context)
        }
        return paid
    }

    /**
     * Settles the bill [text] pays, if it is a payment confirmation for [rule]. [isStatement] says the
     * message comes from where this rule's bills come from; one that the bill pattern itself matches
     * is the statement, not a payment ("payment due by..." would otherwise settle its own bill).
     */
    /** Whether a bill on file with [onFile] and a new message reading [incoming] are the same bill
     *  (given the same rule and due date): equal amounts to the cent, or no amount on one side. */
    internal fun sameCycle(onFile: Double?, incoming: Double?): Boolean =
        onFile == null || incoming == null || kotlin.math.abs(onFile - incoming) < 0.005

    /** A number in a message: digits with grouping and decimals, not touching a date or time
     *  separator, so "5-Mar-2027" and "10:30" aren't read as amounts. */
    private val AMOUNT_TOKEN = Regex("""(?<![\d.,/:\-])\d[\d,.]*(?<![.,])(?![\d/:\-])""")

    /** Words that introduce what a bill asks for, with at most a currency in between. */
    private val AMOUNT_CONTEXT = Regex(
        "(?i)\\b(total|due|outstanding|payable|amount|amt|balance due|statement balance)\\b[^\\d]{0,15}$",
    )

    /** Words that make the number after them something other than a bill amount: a card's last
     *  digits ("ending 1500", "XX1500"), its limit, an account or reference number. */
    private val NOT_AN_AMOUNT = Regex("(?i)(ending( in| with)?|\\bx{2,}|\\*{2,}|limit|available|a/c( no)?|acc(oun)?t( no)?|ref(erence)?( no)?)[^\\d]{0,15}$")

    /** Whether [text] states [amount] anywhere. A bare whole number under 32 never counts: it is as
     *  likely a day of the month as an amount. */
    internal fun mentionsAmount(text: String, amount: Double?): Boolean {
        if (amount == null) return false
        if (amount < 32 && amount == kotlin.math.floor(amount)) return false
        return AMOUNT_TOKEN.findAll(text).any { t ->
            val before = text.substring(maxOf(0, t.range.first - 30), t.range.first)
            // Only a number the message presents as what's owed counts: right after "total",
            // "due", "outstanding", "amount"... An available limit, a card's digits or a reference
            // that happens to equal the bill on file would otherwise merge two cards' bills.
            AMOUNT_CONTEXT.containsMatchIn(before) && !NOT_AN_AMOUNT.containsMatchIn(before) &&
                ReminderPolicy.parseAmount(t.value)?.let { sameCycle(amount, it) } == true
        }
    }

    /**
     * Whether a message from the statement's own source is the statement rather than a payment.
     * With a match pattern, that decides. Without one (blank matches everything), a message that
     * produced a bill, or that the rule can read a due date out of, is the statement: a pay pattern
     * as loose as "payment" would otherwise settle the bill from its own "payment due by ..." text.
     */
    private fun looksLikeStatement(context: Context, rule: ReminderRuleEntity, text: String, atMs: Long, msgKey: String): Boolean {
        if (rule.matchPattern.isNotBlank()) return ReminderExtractor.matches(rule.matchPattern, text)
        val becameBill = try {
            NotiDatabase.get(context).billOccurrenceDao().bySourceKey(key("${rule.id}|$msgKey")) != null
        } catch (e: Exception) { false }
        if (becameBill) return true
        return ReminderExtractor.extractBill(
            text = text, matchPattern = null, amountPattern = null, currencyPattern = null,
            datePattern = rule.datePattern, dateFormat = rule.dateFormat,
            today = ReminderPolicy.fromEpochMillis(atMs).toLocalDate(),
        )?.dueDate != null
    }

    private fun applyPayment(
        context: Context,
        rule: ReminderRuleEntity,
        text: String,
        atMs: Long,
        msgKey: String,
        isStatement: Boolean,
    ): Boolean {
        if (!ReminderExtractor.matches(rule.payMatchPattern, text)) return false
        if (isStatement && looksLikeStatement(context, rule, text, atMs, msgKey)) return false
        val dao = NotiDatabase.get(context).billOccurrenceDao()
        val settleKey = key("pay|${rule.id}|$msgKey")
        return try {
            if (dao.countBySettleKey(settleKey) > 0) return false // this payment already settled one
            val amount = ReminderPolicy.parseAmount(ReminderExtractor.capture(rule.payAmountPattern, text))
            val target = PaymentResolver.pick(dao.activeForRule(rule.id), atMs, amount, rule.payMatchAmount == 1)
            if (target == null) {
                Diag.log("payment for \"${rule.name}\" seen, but no outstanding bill fits it" +
                    if (rule.payMatchAmount == 1) " (amount ${amount ?: "unreadable"})" else "")
                return false
            }
            if (dao.settleAutomatically(target.id, System.currentTimeMillis(), settleKey) == 0) return false
            ReminderScheduler.cancelBill(context, target.id)
            ReminderNotifier.cancelBill(context, target.id)
            Diag.log("payment: \"${target.name}\" marked paid automatically")
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- Parking ----

    private fun applyParking(context: Context, rule: ReminderRuleEntity, text: String, atMs: Long): Boolean {
        val match = ReminderExtractor.extractParking(
            text = text,
            matchPattern = rule.matchPattern,
            zonePattern = rule.zonePattern,
            expiryPattern = rule.expiryPattern,
            dateFormat = rule.dateFormat,
            now = ReminderPolicy.fromEpochMillis(atMs),
        ) ?: return false
        return ParkingTracker.onMatch(context, rule, match.zone.orEmpty(), match.expiry, atMs)
    }

    // ---- Helpers ----

    /** A due date as stored - zone-free, see [DueDates]. */
    private fun midnightMs(date: LocalDate): Long = DueDates.toMs(date)

    /** Short stable hash - the same message seen twice must produce the same key. */
    private fun key(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

    /** Purges settled bills past the history window. */
    fun purgeHistory(context: Context) {
        val days = Settings.get(context).reminderHistoryDays
        if (days <= 0) return
        try {
            NotiDatabase.get(context).billOccurrenceDao()
                .purgeHistoryBefore(System.currentTimeMillis() - days * 86_400_000L)
        } catch (e: Exception) {
            // best-effort
        }
    }
}
