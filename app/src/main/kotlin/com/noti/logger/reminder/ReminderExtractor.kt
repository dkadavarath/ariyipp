package com.noti.logger.reminder

import android.content.Context
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

    /**
     * A user-written pattern, compiled. Most run on RE2/J, a linear-time engine: however the
     * pattern is written, matching costs time proportional to the text, so a careless `(a+)+$` can't
     * hang the scan that runs every rule over every arriving message. (Android's own regex engine is
     * native ICU, which backtracks exponentially and can't be interrupted.) A pattern using what RE2
     * leaves out - lookarounds, backreferences - falls back to the platform engine, run on a worker
     * thread with a time limit ([MATCH_BUDGET_MS]); past it the match counts as failed and the
     * pattern is remembered as slow.
     */
    sealed interface Compiled {
        class Linear(val p: com.google.re2j.Pattern) : Compiled
        class Backtracking(val r: Regex) : Compiled
    }

    private object Invalid : Compiled

    /** Compiled patterns by source text. A scan applies every rule's 2-4 patterns to every message
     *  in the window, so without this the same handful of regexes were recompiled thousands of
     *  times per rescan. Access-ordered and capped, so the rule editor's live typing can't grow it;
     *  failures are cached too (as [Invalid]) so a broken pattern isn't re-parsed per message. */
    private val cache = object : LinkedHashMap<String, Compiled>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Compiled>?) = size > 64
    }

    /** Compiles [pattern], or returns null if it is blank or invalid. */
    fun compile(pattern: String?): Compiled? {
        if (pattern.isNullOrBlank()) return null
        val c = synchronized(cache) {
            cache.getOrPut(pattern) {
                try {
                    Compiled.Linear(com.google.re2j.Pattern.compile(
                        pattern, com.google.re2j.Pattern.CASE_INSENSITIVE or com.google.re2j.Pattern.DOTALL,
                    ))
                } catch (e: Exception) {
                    try {
                        Compiled.Backtracking(Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)))
                    } catch (e2: Exception) {
                        Invalid
                    }
                }
            }
        }
        return c.takeIf { it !== Invalid }
    }

    /** How long a fallback (backtracking) pattern may spend on one message. */
    const val MATCH_BUDGET_MS = 250L

    /** Patterns that ran out of time, so the editor can say which. Kept on disk ([attach]): a
     *  runaway match can't be stopped, only abandoned, and each abandoned one keeps a thread busy
     *  until it finishes - so a pattern is allowed to do that once per install, not once per start. */
    private val slow = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private const val SLOW_PREFS = "reminder_slow_patterns"
    private const val SLOW_KEY = "patterns"
    private const val SLOW_KEEP = 50
    @Volatile private var appContext: Context? = null
    @Volatile private var prefs: android.content.SharedPreferences? = null

    /** Remember slow patterns on disk from now on. Only stores the context: the file is read on
     *  first use, off the main thread (matching never runs on it). Plain preferences: these are
     *  the user's own rule patterns, already in the encrypted database. */
    fun attach(context: Context) { appContext = context.applicationContext }

    private fun loaded() {
        if (prefs != null) return
        val ctx = appContext ?: return
        synchronized(slow) {
            if (prefs != null) return
            val p = ctx.getSharedPreferences(SLOW_PREFS, Context.MODE_PRIVATE)
            p.getStringSet(SLOW_KEY, null)?.let { slow.addAll(it) }
            prefs = p
        }
    }

    private fun rememberSlow(pattern: String): Boolean {
        val added = synchronized(slow) {
            if (!slow.add(pattern)) return false
            while (slow.size > SLOW_KEEP) slow.remove(slow.first())
            true
        }
        if (added) prefs?.edit()?.putStringSet(SLOW_KEY, HashSet(slow))?.apply()
        return added
    }

    fun isSlow(pattern: String?): Boolean = pattern != null && run { loaded(); pattern in slow }

    /** Fallback matching off the caller's thread, bounded. A runaway native match can't be
     *  stopped, only abandoned, so a slow pattern is skipped from then on rather than retried. */
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "reminder-regex").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private fun <T> timed(pattern: String, block: () -> T): T? {
        if (isSlow(pattern)) return null
        val f = pool.submit<T> { block() }
        return try {
            f.get(MATCH_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            f.cancel(true)
            if (rememberSlow(pattern)) com.noti.shared.Diag.log("reminders: a pattern took over ${MATCH_BUDGET_MS} ms and was stopped - simplify it")
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Whether [pattern] is found in [text]; false for a blank/invalid/slow pattern. */
    private fun find(pattern: String, text: String): Boolean = when (val c = compile(pattern)) {
        is Compiled.Linear -> c.p.matcher(text).find()
        is Compiled.Backtracking -> timed(pattern) { c.r.containsMatchIn(text) } ?: false
        else -> false
    }

    /** The match's groups (0 = whole match), or null. */
    private fun groups(pattern: String, text: String): List<String>? = when (val c = compile(pattern)) {
        is Compiled.Linear -> c.p.matcher(text).let { m -> if (m.find()) (0..m.groupCount()).map { m.group(it).orEmpty() } else null }
        is Compiled.Backtracking -> timed(pattern) { c.r.find(text)?.groupValues }
        else -> null
    }

    /** True when [pattern] finishes within budget on [sample]. Linear patterns always do. */
    fun finishesInTime(pattern: String?, sample: String): Boolean {
        if (pattern.isNullOrBlank()) return true
        return when (val c = compile(pattern)) {
            is Compiled.Backtracking -> timed(pattern) { c.r.containsMatchIn(sample) } != null
            else -> true
        }
    }

    /** Why a pattern won't compile, for the editor's inline error. Null when it's fine (or blank). */
    fun patternError(pattern: String?): String? {
        if (pattern.isNullOrBlank()) return null
        val c = compile(pattern) ?: return try {
            Regex(pattern); "invalid regular expression"
        } catch (e: Exception) {
            e.message ?: "invalid regular expression"
        }
        // Only a fallback pattern can run away. Long runs of one kind of character with a mismatch
        // at the end are what make nested repetition backtrack exponentially.
        if (c is Compiled.Backtracking && (isSlow(pattern) || probesFor(pattern).any { !finishesInTime(pattern, it) })) {
            return SLOW_MESSAGE
        }
        return null
    }

    const val SLOW_MESSAGE = "takes too long on long messages - simplify repeated groups like (a+)+"

    private val SLOW_PROBES = listOf(
        "a".repeat(2_000) + "!", "1".repeat(2_000) + "x", " ".repeat(2_000) + "x", "aA1 -.,".repeat(300) + "\u0000",
    )

    /** The generic probes, plus a long run of each character the pattern itself names: a nested
     *  group only runs away on text it can keep matching, and that's text made of its own letters. */
    private fun probesFor(pattern: String): List<String> =
        SLOW_PROBES + pattern.filter { it.isLetterOrDigit() }.toSet().take(6).map { ch -> ch.toString().repeat(2_000) + "\u0000" }

    /** First capture group if the pattern has one, else the whole match, else null. */
    fun capture(pattern: String?, text: String): String? {
        if (pattern.isNullOrBlank()) return null
        val g = groups(pattern, text) ?: return null
        // groups[0] is always the full match. If the pattern *has* a group, that group is the answer
        // even when it matched empty - falling back to the whole match there would hand back the
        // surrounding text ("INR ") as if it were the amount. Only a pattern with no group at all
        // falls back to what it matched.
        if (g.size > 1) return g[1].takeIf { it.isNotBlank() }
        return g[0].takeIf { it.isNotBlank() }
    }

    /** Does this message belong to this rule at all? A blank match pattern matches everything from
     *  the configured source, which is a legitimate setup for an app that only ever sends bills. */
    fun matches(matchPattern: String?, text: String): Boolean {
        if (matchPattern.isNullOrBlank()) return true
        return find(matchPattern, text) // a broken pattern matches nothing
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
