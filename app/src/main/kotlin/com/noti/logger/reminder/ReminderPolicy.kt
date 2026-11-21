package com.noti.logger.reminder

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    private val FALLBACK_DATE_PATTERNS = listOf(
        "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd", "dd/MM/yy", "dd-MM-yy",
        "dd-MMM-yyyy", "dd MMM yyyy", "dd-MMM-yy", "dd MMM yy", "MMM dd, yyyy", "d MMMM yyyy",
        // Year-less, tried last: plenty of banks send "due by 07-Feb" and leave the year implied.
        // These come after the full forms so a dated message is never matched by a shorter pattern.
        "dd-MMM", "dd MMM", "dd/MM", "dd-MM", "MMM dd", "d MMMM",
    )

    private val FALLBACK_DATETIME_PATTERNS = listOf(
        "dd/MM/yyyy HH:mm", "dd-MM-yyyy HH:mm", "yyyy-MM-dd HH:mm", "dd/MM/yy HH:mm",
        "dd-MMM-yyyy HH:mm", "dd MMM yyyy HH:mm", "dd/MM/yyyy hh:mm a", "dd MMM yyyy hh:mm a",
        // Year-less, last for the same reason as the date list above.
        "dd/MM HH:mm", "dd-MM HH:mm", "dd-MMM HH:mm", "dd MMM HH:mm",
    )

    private val FALLBACK_TIME_PATTERNS = listOf("HH:mm", "hh:mm a", "h:mm a", "HH.mm")

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
        LocalTime.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
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
     * Returns the first occurrence that is both later than [after] - the last one already recorded,
     * if any - and not in the past, so a rule set up months ago yields the next real due date
     * rather than a backlog of missed ones. Null when no further occurrence exists.
     */
    fun nextManualDue(
        anchorStart: LocalDate,
        recurrence: Recurrence,
        every: Int,
        offsetDays: Int,
        after: LocalDate?,
        today: LocalDate,
    ): LocalDate? {
        var anchor = anchorStart
        var periods = 0
        while (periods < MAX_PERIODS) {
            val due = anchor.plusDays(offsetDays.toLong().coerceAtLeast(0))
            val isNew = after == null || due.isAfter(after)
            if (isNew && !due.isBefore(today)) return due
            if (recurrence == Recurrence.NEVER) {
                // A one-off still counts the first time round even if its date has passed: an
                // overdue bill has to show up, not be skipped for being late.
                return if (isNew) due else null
            }
            anchor = nextRecurrence(anchor, recurrence, every)
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

    /** When to remind about a parking session expiring at [expiry]. */
    fun parkingReminderAt(expiry: LocalDateTime, leadMinutes: Int = DEFAULT_PARKING_LEAD_MINUTES): LocalDateTime =
        expiry.minusMinutes(leadMinutes.toLong().coerceAtLeast(0))

    /**
     * Pushes reminders that would collide onto their own minutes, [spacingMinutes] apart, keeping
     * the given order. Only equal or already-too-close times are moved, so a day with one bill
     * fires exactly when asked.
     */
    fun spaceOut(times: List<LocalDateTime>, spacingMinutes: Int = DEFAULT_SPACING_MINUTES): List<LocalDateTime> {
        if (spacingMinutes <= 0) return times
        val out = ArrayList<LocalDateTime>(times.size)
        var last: LocalDateTime? = null
        for (t in times) {
            val slot = if (last != null && t.isBefore(last.plusMinutes(spacingMinutes.toLong()))) {
                last.plusMinutes(spacingMinutes.toLong())
            } else {
                t
            }
            out.add(slot)
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
