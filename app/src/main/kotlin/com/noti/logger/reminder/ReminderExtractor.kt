package com.noti.logger.reminder

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Applies a rule's regexes to one message. Pure - no Room, no Android - so the whole matching
 * behaviour is unit-testable and the rule editor can run it live against pasted sample text.
 *
 * Each value gets its own field rather than one big regex with named groups. A bank changing its
 * wording then breaks one field instead of all of them: an entry whose amount still reads but whose
 * date no longer does lands in the tab needing a date, which is far more useful than nothing at
 * all. It is also the difference between a regex you can debug and one you can't.
 *
 * Every regex here takes **the first capture group** if it has one, else the whole match. That one
 * rule keeps the in-app guide short, which matters more than flexibility for something a person has
 * to write by hand on a phone.
 */
object ReminderExtractor {

    /** A regex that failed to compile is reported, not thrown - the rule editor shows which field
     *  is wrong, and a bad rule can never take down a scan of every other rule. */
    class BadPattern(val field: String, val message: String)

    /** What a bill rule got out of a message. [dueDate] null means "matched, but no date" - the
     *  occurrence is still created so it can be completed by hand. */
    data class BillMatch(
        val amountRaw: String?,
        val amount: Double?,
        val currency: String?,
        val dueDateRaw: String?,
        val dueDate: LocalDate?,
    )

    /** What a parking rule got out of a message. Either field may be null; the caller raises the
     *  "needs your input" notification when one is. */
    data class ParkingMatch(
        val zone: String?,
        val expiryRaw: String?,
        val expiry: LocalDateTime?,
    )

    /** Compiled patterns by source text. A scan applies every rule's 2-4 patterns to every message
     *  in the window, so without this the same handful of regexes were recompiled thousands of
     *  times per rescan. Access-ordered and capped, so the rule editor's live typing can't grow it;
     *  failures are cached too (as [INVALID]) so a broken pattern isn't re-parsed per message. */
    private val INVALID = Regex("(?!)")
    private val cache = object : LinkedHashMap<String, Regex>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?) = size > 64
    }

    /** Compiles [pattern], or returns null if it is blank or invalid. */
    fun compile(pattern: String?): Regex? {
        if (pattern.isNullOrBlank()) return null
        val regex = synchronized(cache) {
            cache.getOrPut(pattern) {
                try {
                    Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                } catch (e: Exception) {
                    INVALID
                }
            }
        }
        return regex.takeIf { it !== INVALID }
    }

    /** Why a pattern won't compile, for the editor's inline error. Null when it's fine (or blank). */
    fun patternError(pattern: String?): String? {
        if (pattern.isNullOrBlank()) return null
        return try {
            Regex(pattern); null
        } catch (e: Exception) {
            e.message ?: "invalid regular expression"
        }
    }

    /** First capture group if the pattern has one, else the whole match, else null. */
    fun capture(pattern: String?, text: String): String? {
        val regex = compile(pattern) ?: return null
        val match = regex.find(text) ?: return null
        // groupValues[0] is always the full match. If the pattern *has* a group, that group is the
        // answer even when it matched empty - falling back to the whole match there would hand back
        // the surrounding text ("INR ") as if it were the amount. Only a pattern with no group at
        // all falls back to what it matched.
        if (match.groupValues.size > 1) return match.groupValues[1].takeIf { it.isNotBlank() }
        return match.value.takeIf { it.isNotBlank() }
    }

    /** Does this message belong to this rule at all? A blank match pattern matches everything from
     *  the configured source, which is a legitimate setup for an app that only ever sends bills. */
    fun matches(matchPattern: String?, text: String): Boolean {
        if (matchPattern.isNullOrBlank()) return true
        val regex = compile(matchPattern) ?: return false // a broken pattern matches nothing
        return regex.containsMatchIn(text)
    }

    /**
     * Runs a bill rule over [text]. Returns null when the message isn't this rule's at all;
     * a non-null result with null fields means "this is ours, but we couldn't read some of it".
     */
    fun extractBill(
        text: String,
        matchPattern: String?,
        amountPattern: String?,
        currencyPattern: String?,
        datePattern: String?,
        dateFormat: String?,
        today: LocalDate = LocalDate.now(),
    ): BillMatch? {
        if (!matches(matchPattern, text)) return null
        val amountRaw = capture(amountPattern, text)
        val dueRaw = capture(datePattern, text)
        return BillMatch(
            amountRaw = amountRaw,
            amount = ReminderPolicy.parseAmount(amountRaw),
            currency = capture(currencyPattern, text)?.trim(),
            dueDateRaw = dueRaw,
            dueDate = ReminderPolicy.parseDate(dueRaw, dateFormat, today),
        )
    }

    /** Runs a parking rule over [text]. Null when the message isn't this rule's. */
    fun extractParking(
        text: String,
        matchPattern: String?,
        zonePattern: String?,
        expiryPattern: String?,
        dateFormat: String?,
        now: LocalDateTime = LocalDateTime.now(),
    ): ParkingMatch? {
        if (!matches(matchPattern, text)) return null
        val expiryRaw = capture(expiryPattern, text)
        return ParkingMatch(
            zone = capture(zonePattern, text)?.trim(),
            expiryRaw = expiryRaw,
            expiry = ReminderPolicy.parseDateTime(expiryRaw, dateFormat, now),
        )
    }

    /** The searchable text of a captured notification: title, text, big text and subtext together,
     *  so a rule can match on whichever line the bank actually put the number in. */
    fun notificationText(title: String?, text: String?, bigText: String?, subText: String?): String =
        listOfNotNull(title, text, bigText, subText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
}
