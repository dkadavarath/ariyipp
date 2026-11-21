package com.noti.logger.reminder

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * The date and money arithmetic behind reminders, kept pure so all of it can be unit-tested without
 * a device. Nothing here touches Room, AlarmManager or the notification manager.
 *
 * Everything works in the device's local time zone by design: a bill due "on the 28th" is due on
 * the 28th where you are, and a parking session expires at a wall-clock time on a sign, neither of
 * which means anything in UTC.
 */
object ReminderPolicy {

    /** A per-entry lead of this value means "use whatever the global setting says". */
    const val LEAD_FROM_GLOBAL = -1

    /** Resolves an entry's own lead against the global default. */
    fun effectiveLead(entryLead: Int, globalLead: Int): Int =
        if (entryLead < 0) globalLead else entryLead

    /** Bill reminders fire the day before, at this hour by default. */
    const val DEFAULT_REMIND_DAYS_BEFORE = 1
    const val DEFAULT_REMIND_HOUR = 9
    const val DEFAULT_REMIND_MINUTE = 0

    /**
     * Gap between reminders that would otherwise land on the same minute. Several bills falling due
     * the same day would otherwise collapse into one buzz and one glanced-at notification; spacing
     * them makes each one land as its own event with its own name.
     */
    const val DEFAULT_SPACING_MINUTES = 5

    /**
     * How long before expiry a parking reminder fires. Non-zero on purpose: without exact-alarm
     * permission an alarm fires in a Doze maintenance window and can run several minutes late, so
     * a lead time is what keeps a late alarm still *early* enough to act on. Set it to 0 to be
     * reminded exactly at expiry and accept the drift.
     */
    const val DEFAULT_PARKING_LEAD_MINUTES = 10

    /** Default scan cadence, in hours. Once a day, as asked. */
    const val DEFAULT_SCAN_HOURS = 24

    /** How far back the very first scan looks. Bounded because captured notifications are purged on
     *  the retention schedule anyway, so looking further back would only find nothing, slowly. */
    const val FIRST_SCAN_LOOKBACK_DAYS = 35

    // ---- Amounts ----

    /**
     * The numeric value of a captured amount: strips thousands separators, currency symbols and
     * stray spaces, and keeps the decimal point. Returns null when nothing numeric is left, which
     * the UI shows as "amount not captured" rather than as zero - a bill for 0.00 and a bill whose
     * amount we failed to read are very different things.
     */
    fun parseAmount(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        // Keep digits, dot, comma and a leading minus; then decide what the comma was doing.
        // Then drop separators stranded at either end: the dot of "Rs." / "Rs.-" ahead of the number
        // and a sentence's full stop after it would otherwise read as a second decimal point.
        val cleaned = raw.trim().filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
            .trimStart('.', ',')
            .trimEnd('.', ',', '-')
        if (cleaned.isEmpty()) return null
        // Which separator is the decimal point? Getting this backwards changes a bill by 1000x,
        // so the test is deliberately narrow: a comma only means "decimal" when it can't plausibly
        // be a thousands separator - either a dot already did the grouping ("1.234,56"), or the
        // comma isn't followed by exactly three digits ("1,50" is one and a half, "12,480" is not).
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        val digitsAfterComma = if (lastComma >= 0) cleaned.length - lastComma - 1 else -1
        val commaIsDecimal = cleaned.count { it == ',' } == 1 && lastComma > lastDot &&
            (lastDot >= 0 || digitsAfterComma != 3)
        val normalised = if (commaIsDecimal) {
            cleaned.replace(".", "").replace(',', '.')
        } else {
            cleaned.replace(",", "")
        }
        return normalised.toDoubleOrNull()
    }

    // ---- Dates out of messages ----

    /**
     * Parses a captured date against a user-supplied pattern, then against a short list of shapes
     * banks actually use. The explicit pattern wins; the fallbacks exist so a rule that matched the
     * message but has no pattern set still usually produces a date rather than nothing.
     */
    fun parseDate(raw: String?, pattern: String?, today: LocalDate = LocalDate.now()): LocalDate? {
        if (raw.isNullOrBlank()) return null
        // A capture that runs to the end of the sentence ("due by 06-OCT-26.") brings the full
        // stop with it, which no date pattern accepts.
        val text = raw.trim().trimEnd('.', ',', ';', ':')
        if (!pattern.isNullOrBlank()) {
            tryParseDate(text, pattern, today)?.let { return it }
        }
        for (p in FALLBACK_DATE_PATTERNS) {
            tryParseDate(text, p, today)?.let { return it }
        }
        // A bare day-of-month ("due on 28") is common in terse SMS; read it as the next such day.
        text.toIntOrNull()?.let { day -> if (day in 1..31) return nextDayOfMonth(day, today) }
        return null
    }

    /** As [parseDate] but for a parking ticket's expiry, which needs the time of day too. */
    fun parseDateTime(raw: String?, pattern: String?, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        if (!pattern.isNullOrBlank()) {
            tryParseDateTime(text, pattern, now)?.let { return it }
            // The pattern may describe a time only ("HH:mm"), which is very common on a ticket that
            // assumes today. Anchor it to today, rolling to tomorrow if that moment already passed.
            tryParseTime(text, pattern)?.let { return anchorTime(it, now) }
        }
        for (p in FALLBACK_DATETIME_PATTERNS) {
            tryParseDateTime(text, p, now)?.let { return it }
        }
        for (p in FALLBACK_TIME_PATTERNS) {
            tryParseTime(text, p)?.let { return anchorTime(it, now) }
        }
        return null
    }

    /** A bare time on a parking ticket means today - unless today's is already gone, which on a
     *  ticket bought at 23:50 for 00:30 means tomorrow. */
    private fun anchorTime(time: LocalTime, now: LocalDateTime): LocalDateTime {
        val todays = LocalDateTime.of(now.toLocalDate(), time)
        return if (todays.isBefore(now)) todays.plusDays(1) else todays
    }

    /*
     * Single-letter d / M / H / h take one *or* two digits, so "7 Feb 2026" and "5/1/2027" read as
     * well as "07 Feb 2026" and "05/01/2027" - "dd" demanded two and silently failed them. Numeric
     * dates are read day-first (dd/MM), as banks in the UAE and India write them. A four-digit year
     * is tried before a two-digit one: "yy" can't take "2027", and the order keeps it from trying.
     */
    private val FALLBACK_DATE_PATTERNS = listOf(
        "d/M/yyyy", "d-M-yyyy", "d.M.yyyy", "yyyy-M-d", "d/M/yy", "d-M-yy", "d.M.yy",
        "d-MMM-yyyy", "d MMM yyyy", "d-MMM-yy", "d MMM yy", "MMM d, yyyy", "MMM d yyyy",
        "d MMMM yyyy", "MMMM d, yyyy", "d MMM, yyyy",
        // Year-less, tried last: plenty of banks send "due by 07-Feb" and leave the year implied.
        // These come after the full forms so a dated message is never matched by a shorter pattern.
        "d-MMM", "d MMM", "d/M", "d-M", "MMM d", "d MMMM", "MMMM d",
    )

    private val FALLBACK_DATETIME_PATTERNS = listOf(
        "d/M/yyyy H:mm", "d-M-yyyy H:mm", "yyyy-M-d H:mm", "d/M/yy H:mm",
        "d-MMM-yyyy H:mm", "d MMM yyyy H:mm",
        "d/M/yyyy h:mm a", "d-M-yyyy h:mm a", "d MMM yyyy h:mm a", "d-MMM-yyyy h:mm a",
        "d/M/yyyy h:mma", "d MMM yyyy h:mma",
        // Year-less, last for the same reason as the date list above.
        "d/M H:mm", "d-M H:mm", "d-MMM H:mm", "d MMM H:mm", "d MMM h:mm a", "d/M h:mm a",
    )

    /** "14:30", "2:30 pm", "02:30 PM", "2:30pm", "14.30". Case-insensitive (see [formatter]). */
    private val FALLBACK_TIME_PATTERNS = listOf("H:mm", "h:mm a", "h:mma", "H.mm")

    /**
     * Parses [text] against [pattern], supplying a year when the pattern has none.
     *
     * A year-less date is resolved against [reference]: the same day this year, rolled forward a
     * year when that would put it far in the past. A December statement saying "due 05-Jan" means
     * next January, not eleven months ago. The roll only happens beyond
     * [YEARLESS_BACKDATE_GRACE_DAYS], so a genuinely overdue bill stays overdue and visible rather
     * than being quietly rescheduled twelve months out - which would hide exactly the bill you most
     * need to see.
     */
    private fun tryParseDate(text: String, pattern: String, reference: LocalDate): LocalDate? = try {
        val hasYear = patternHasYear(pattern)
        val parsed = LocalDate.parse(text, formatter(pattern, if (hasYear) null else reference.year))
        if (!hasYear && parsed.isBefore(reference.minusDays(YEARLESS_BACKDATE_GRACE_DAYS))) {
            parsed.plusYears(1)
        } else {
            parsed
        }
    } catch (e: Exception) {
        null
    }

    /** Whether [pattern] parses a year itself ('y' year-of-era or 'u' proleptic year), ignoring
     *  letters inside quoted literals. */
    private fun patternHasYear(pattern: String): Boolean =
        pattern.replace(Regex("'[^']*'"), "").any { it == 'y' || it == 'u' }

    /**
     * Case-insensitive, because banks shout: "06-OCT-26" must read as well as "06-Oct-26" (MMM is
     * otherwise case-sensitive and silently failed every upper-case month). [defaultYear] is only
     * set for a year-less pattern - defaulting YEAR alongside a parsed 'yy'/'yyyy' (year-of-era)
     * makes the two disagree whenever the message's year isn't this year, so every date from
     * another year (a December statement read in January) failed to parse.
     */
    private fun formatter(pattern: String, defaultYear: Int?) =
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .apply { if (defaultYear != null) parseDefaulting(ChronoField.YEAR, defaultYear.toLong()) }
            .toFormatter(Locale.ENGLISH)

    /** How far in the past a year-less date may land before it is read as next year's. */
    const val YEARLESS_BACKDATE_GRACE_DAYS = 60L

    /** As [tryParseDate], but for a date-and-time; a missing year defaults to [reference]'s. */
    private fun tryParseDateTime(text: String, pattern: String, reference: LocalDateTime): LocalDateTime? = try {
        val hasYear = patternHasYear(pattern)
        val parsed = LocalDateTime.parse(text, formatter(pattern, if (hasYear) null else reference.year))
        if (!hasYear && parsed.isBefore(reference.minusDays(YEARLESS_BACKDATE_GRACE_DAYS))) {
            parsed.plusYears(1)
        } else {
            parsed
        }
    } catch (e: Exception) {
        null
    }

    private fun tryParseTime(text: String, pattern: String): LocalTime? = try {
        LocalTime.parse(text, formatter(pattern, null))
    } catch (e: Exception) {
        null
    }

    // ---- Due dates ----

    /** The next occurrence of [day] as a day-of-month, on or after [from], clamped into short
     *  months so "the 31st" still means month-end in February. */
    fun nextDayOfMonth(day: Int, from: LocalDate): LocalDate {
        val thisMonth = clampToMonth(YearMonth.from(from), day)
        return if (!thisMonth.isBefore(from)) thisMonth else clampToMonth(YearMonth.from(from).plusMonths(1), day)
    }

    private fun clampToMonth(month: YearMonth, day: Int): LocalDate =
        month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    /**
     * The due date for a bill, given how the rule says to work it out.
     *
     * [anchorDate] is the message's own date for a matched rule, or "today" for a manual one;
     * [CALCULATED] counts [offsetDays] from the anchor day of the period, which is the statement
     * pattern: statement on the 10th, due 28 days later.
     */
    fun dueDate(
        mode: DueMode,
        anchorDate: LocalDate,
        constantDay: Int = 1,
        anchorDay: Int = 1,
        offsetDays: Int = 0,
        extracted: LocalDate? = null,
    ): LocalDate? = when (mode) {
        DueMode.FROM_MESSAGE -> extracted
        DueMode.CONSTANT -> nextDayOfMonth(constantDay, anchorDate)
        DueMode.CALCULATED -> {
            // Count from this period's anchor day if it has already passed, otherwise the previous
            // one - a statement dated the 12th belongs to this month's 10th anchor, not next month's.
            val thisAnchor = clampToMonth(YearMonth.from(anchorDate), anchorDay)
            val base = if (!thisAnchor.isAfter(anchorDate)) thisAnchor
            else clampToMonth(YearMonth.from(anchorDate).minusMonths(1), anchorDay)
            base.plusDays(offsetDays.toLong())
        }
    }

    /**
     * Steps a date on by [every] units of [recurrence]. [Recurrence.NEVER] does not move.
     *
     * The interval is what makes this usable for real bills: a card cycle is 28 days, not "monthly",
     * and a permit might be every 3 years. Stepping by whole units also keeps month arithmetic
     * honest - plusMonths clamps 31 January to 28 February rather than spilling into March.
     */
    fun nextRecurrence(previous: LocalDate, recurrence: Recurrence, every: Int = 1): LocalDate {
        val n = every.coerceAtLeast(1).toLong()
        return when (recurrence) {
            Recurrence.DAILY -> previous.plusDays(n)
            Recurrence.WEEKLY -> previous.plusWeeks(n)
            Recurrence.MONTHLY -> previous.plusMonths(n)
            Recurrence.YEARLY -> previous.plusYears(n)
            Recurrence.NEVER -> previous
        }
    }

    /** [start] stepped on by [n] × [every] units, computed from [start] each time so a clamped
     *  month (31 → 28 Feb) doesn't carry into the months after it. Pure. */
    fun nthRecurrence(start: LocalDate, recurrence: Recurrence, every: Int, n: Int): LocalDate {
        val k = every.coerceAtLeast(1).toLong() * n
        return when (recurrence) {
            Recurrence.DAILY -> start.plusDays(k)
            Recurrence.WEEKLY -> start.plusWeeks(k)
            Recurrence.MONTHLY -> start.plusMonths(k)
            Recurrence.YEARLY -> start.plusYears(k)
            Recurrence.NEVER -> start
        }
    }

    /** Guards the search below against a rule that somehow cannot advance. */
    private const val MAX_PERIODS = 4_000

    /**
     * The next due date for a manual bill, covering both shapes the user can set up:
     *
     *  - a date that simply repeats - [anchorStart] is that date and [offsetDays] is 0;
     *  - a bill raised on one date and due some days later - [anchorStart] is the date it is raised
     *    ("the 28th"), [offsetDays] the grace ("due in 10 days"). The *anchor* is what recurs and
     *    the due date follows it, which is the only way the two stay in step: stepping the due date
     *    directly would drift every time a month is short.
     *
     * With no [after] (nothing recorded yet), returns the first occurrence not in the past, so a
     * rule set up months ago yields the next real due date rather than a backlog of missed ones.
     * With [after] - the last one recorded - an occurrence that is already overdue still counts: a
     * bill paid late must not make the following cycle vanish. If several have passed (a rule left
     * idle), only the most recent overdue one is returned, not a backlog to pay through one by one.
     * Null when no further occurrence exists.
     *
     * Each occurrence is counted from [anchorStart] (start + n periods), never from the previous
     * one: stepping 31 Jan → 28 Feb → 28 Mar would pin a month-end bill to the 28th for good.
     */
    fun nextManualDue(
        anchorStart: LocalDate,
        recurrence: Recurrence,
        every: Int,
        offsetDays: Int,
        after: LocalDate?,
        today: LocalDate,
    ): LocalDate? {
        var periods = 0
        var overdue: LocalDate? = null
        while (periods < MAX_PERIODS) {
            val anchor = nthRecurrence(anchorStart, recurrence, every, periods)
            val due = anchor.plusDays(offsetDays.toLong().coerceAtLeast(0))
            val isNew = after == null || due.isAfter(after)
            if (isNew && !due.isBefore(today)) return overdue ?: due
            if (isNew && after != null) overdue = due
            if (recurrence == Recurrence.NEVER) {
                // A one-off still counts the first time round even if its date has passed: an
                // overdue bill has to show up, not be skipped for being late.
                return if (isNew) due else null
            }
            periods++
        }
        return null
    }

    /**
     * What one recurring charge works out to per month, so charges on different cycles can be
     * added together.
     *
     * Averaged rather than exact: a weekly bill is 52/12 months, not 4, and a 28-day cycle is
     * 365/28 a year - treating either as "about four a month" is the classic way a subscription
     * total ends up quietly wrong. A one-off contributes nothing to a recurring total; it is a
     * charge, not a rate.
     */
    fun monthlyEquivalent(amount: Double, recurrence: Recurrence, every: Int): Double {
        val n = every.coerceAtLeast(1).toDouble()
        return when (recurrence) {
            Recurrence.DAILY -> amount * (DAYS_PER_YEAR / 12.0) / n
            Recurrence.WEEKLY -> amount * (WEEKS_PER_YEAR / 12.0) / n
            Recurrence.MONTHLY -> amount / n
            Recurrence.YEARLY -> amount / (12.0 * n)
            Recurrence.NEVER -> 0.0
        }
    }

    private const val DAYS_PER_YEAR = 365.2425
    private const val WEEKS_PER_YEAR = DAYS_PER_YEAR / 7.0

    // ---- When to actually fire ----

    /** When to remind about a bill due on [due]. */
    fun billReminderAt(
        due: LocalDate,
        daysBefore: Int = DEFAULT_REMIND_DAYS_BEFORE,
        hour: Int = DEFAULT_REMIND_HOUR,
        minute: Int = DEFAULT_REMIND_MINUTE,
    ): LocalDateTime =
        LocalDateTime.of(due.minusDays(daysBefore.toLong().coerceAtLeast(0)), LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))

    /**
     * When to actually ring for a parking session, or null for never. The reminder time itself
     * while it's ahead; once it has passed but the session hasn't expired - a top-up shorter than
     * the lead, or a reboot minutes before expiry - ring right away, since the warning is still
     * worth having. Nothing once it has expired, or when an alarm already rang for this reminder
     * time ([rangForMs]). Pure.
     */
    fun parkingFireAt(remindAtMs: Long, expiresAtMs: Long, nowMs: Long, rangForMs: Long = 0L): Long? = when {
        expiresAtMs <= 0L || expiresAtMs <= nowMs -> null
        rangForMs != 0L && rangForMs == remindAtMs -> null
        remindAtMs > nowMs -> remindAtMs
        else -> nowMs + PARKING_LATE_RING_DELAY_MS
    }

    /** How soon a late parking alarm rings: just enough for the scheduling call to complete. */
    const val PARKING_LATE_RING_DELAY_MS = 5_000L

    /** When to remind about a parking session expiring at [expiry]. */
    fun parkingReminderAt(expiry: LocalDateTime, leadMinutes: Int = DEFAULT_PARKING_LEAD_MINUTES): LocalDateTime =
        expiry.minusMinutes(leadMinutes.toLong().coerceAtLeast(0))

    /**
     * Pushes reminders that would collide onto their own minutes, [spacingMinutes] apart. Only equal
     * or already-too-close times are moved, so a day with one bill fires exactly when asked. The
     * result lines up with [times] index for index.
     *
     * Spaced in time order whatever order [times] comes in: callers list bills by due date, and with
     * per-bill leads that isn't reminder order - walking it as given pushed a 7-day warning behind
     * a 2-day one, days late. Ties keep their given order.
     */
    fun spaceOut(times: List<LocalDateTime>, spacingMinutes: Int = DEFAULT_SPACING_MINUTES): List<LocalDateTime> {
        if (spacingMinutes <= 0) return times
        val out = ArrayList<LocalDateTime>(times)
        var last: LocalDateTime? = null
        for (i in times.indices.sortedWith(compareBy { times[it] })) {
            val t = times[i]
            val slot = if (last != null && t.isBefore(last.plusMinutes(spacingMinutes.toLong()))) {
                last.plusMinutes(spacingMinutes.toLong())
            } else {
                t
            }
            out[i] = slot
            last = slot
        }
        return out
    }

    /** Local wall-clock time as the epoch millis AlarmManager wants. */
    fun toEpochMillis(at: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
        at.atZone(zone).toInstant().toEpochMilli()

    fun fromEpochMillis(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
}
