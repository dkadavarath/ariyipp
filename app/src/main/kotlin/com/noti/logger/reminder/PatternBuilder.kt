package com.noti.logger.reminder

/**
 * Writes a rule pattern from an example: you point at the amount in a real message, this works out
 * the regex that finds it again next month.
 *
 * No model involved, and deliberately so. The task is "find this span in messages shaped like this
 * one", and the user has already supplied the one piece of information that is genuinely hard to
 * infer - *which* span they meant. Anchoring on that beats guessing at it, is instant, adds nothing
 * to the app, and produces a pattern the user can read and edit rather than an opaque one they have
 * to trust.
 *
 * Every candidate is verified against the sample before it is returned: it has to match, and the
 * text it captures has to be exactly what was selected. A pattern that would silently pick up the
 * wrong number is worse than none, so those are discarded rather than offered.
 */
object PatternBuilder {

    /** Which field the selection is for; decides how the span is generalised. */
    enum class Field { IDENTIFY, AMOUNT, CURRENCY, DATE, ZONE, EXPIRY }

    /** [dateFormat] is filled in when the selection looks like a date or time we recognise. */
    data class Result(val pattern: String, val dateFormat: String? = null)

    /** How many preceding words to try as an anchor before giving up. */
    private const val MAX_ANCHOR_WORDS = 5

    /**
     * Builds a pattern for [sample].substring([start], [end]).
     *
     * Returns null when no candidate survives verification - the caller shows that as "couldn't
     * work it out from this selection" rather than filling the field with something untrustworthy.
     */
    fun forSelection(sample: String, start: Int, end: Int, field: Field, preferAnchor: Boolean = false): Result? {
        if (start < 0 || end > sample.length || start >= end) return null
        // An amount selected with its currency ("Rs 18,402.75", "INR 500.00", "₹1,170") narrows to
        // just the number: the extracted amount is numeric anyway, and the prefix left in front
        // becomes the anchor - which is exactly what tells this total apart from the minimum due.
        if (field == Field.AMOUNT) {
            val first = (start until end).firstOrNull { sample[it].isDigit() } ?: return null
            val last = (end - 1 downTo start).first { sample[it].isDigit() }
            if (first != start || last != end - 1) return forSelection(sample, first, last + 1, field, preferAnchor)
        }
        val selection = sample.substring(start, end).trim()
        if (selection.isBlank()) return null

        // The identify pattern is not an extraction - it just has to recognise the message, so the
        // selected phrase itself (with flexible whitespace) is exactly right and needs no anchor.
        if (field == Field.IDENTIFY) {
            val literal = looseLiteral(selection)
            return if (matchesSomewhere(literal, sample)) Result(literal) else null
        }

        val body = generalize(selection, field) ?: return null
        val dateFormat = if (field == Field.DATE || field == Field.EXPIRY) dateFormatOf(selection) else null

        // Widen the anchor until the pattern picks out this span and not a different one. Starting
        // with no anchor keeps the simple cases short and readable.
        // [preferAnchor]: a drafted pattern (nobody looked at it) starts from one anchor word, so it
        // reads "Rs\s*(...)" rather than "the first number in the message" - which only holds while
        // nothing numeric is ever added ahead of it. The bare form is still the fallback below.
        for (words in (if (preferAnchor) 1 else 0)..MAX_ANCHOR_WORDS) {
            val anchor = if (words == 0) "" else looseLiteral(precedingWords(sample, start, words)) + """\s*"""
            if (words > 0 && anchor.isBlank()) break
            val candidate = anchor + "($body)"
            if (capturesExactly(candidate, sample, selection)) return Result(candidate, dateFormat)
        }
        if (preferAnchor) forSelection(sample, start, end, field, preferAnchor = false)?.let { return it }
        // A trailing anchor rescues the case where what precedes the span is not distinctive but
        // what follows it is - a zone ending at a full stop, typically.
        val following = followingLiteral(sample, end)
        if (following.isNotBlank()) {
            val candidate = "($body?)" + """\s*""" + following
            if (capturesExactly(candidate, sample, selection)) return Result(candidate, dateFormat)
        }
        return null
    }

    // ---- Generalising the selected span ----

    /** Turns the selected text into a pattern that will match next month's value too. */
    private fun generalize(selection: String, field: Field): String? = when (field) {
        Field.AMOUNT -> if (selection.any { it.isDigit() }) """[\d,]+\.?\d*""" else null
        Field.CURRENCY -> when {
            // A three-letter code generalises; a symbol is itself.
            selection.length in 2..4 && selection.all { it.isLetter() && it.isUpperCase() } ->
                "[A-Z]{${selection.length}}"
            selection.length <= 2 -> Regex.escape(selection).let { "\\Q$selection\\E" }
            else -> null
        }
        Field.DATE, Field.EXPIRY -> shapeOf(selection)
        Field.ZONE -> """[\w\- ]+"""
        Field.IDENTIFY -> null
    }

    /**
     * A date or time as its shape: digit runs become counted digit classes, month names become a
     * three-letter word, separators stay literal. "07-Feb-2026" becomes `\d{2}-\w{3}-\d{4}`, which
     * still matches next month's statement.
     */
    private fun shapeOf(selection: String): String? {
        if (!selection.any { it.isDigit() }) return null
        val out = StringBuilder()
        var i = 0
        while (i < selection.length) {
            val c = selection[i]
            when {
                c.isDigit() -> {
                    var j = i
                    while (j < selection.length && selection[j].isDigit()) j++
                    out.append("""\d{${j - i}}""")
                    i = j
                }
                c.isLetter() -> {
                    var j = i
                    while (j < selection.length && selection[j].isLetter()) j++
                    out.append("""\w{${j - i}}""")
                    i = j
                }
                else -> {
                    out.append(if (c == ' ') """\s""" else Regex.escape(c.toString()).let { "\\$c" })
                    i++
                }
            }
        }
        return out.toString()
    }

    // ---- Anchors ----

    /** The [count] words immediately before [index], as they appear in the sample. */
    private fun precedingWords(sample: String, index: Int, count: Int): String {
        val before = sample.substring(0, index).trimEnd()
        if (before.isEmpty()) return ""
        val words = before.split(Regex("""\s+"""))
        return words.takeLast(count).joinToString(" ")
    }

    /** The first punctuation or word immediately after [index], for a trailing anchor. */
    private fun followingLiteral(sample: String, index: Int): String {
        val after = sample.substring(index)
        val trimmed = after.trimStart()
        if (trimmed.isEmpty()) return ""
        val first = trimmed.first()
        // A closing punctuation mark is the most useful trailing anchor and the least likely to
        // change between messages.
        return if (!first.isLetterOrDigit()) "\\$first" else looseLiteral(trimmed.split(Regex("""\s+""")).first())
    }

    /** Literal text with whitespace allowed to vary - line breaks and double spaces differ between
     *  a notification and the same message as SMS. */
    private fun looseLiteral(text: String): String =
        text.trim().split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .joinToString("""\s+""") { word -> word.map { escapeChar(it) }.joinToString("") }

    private fun escapeChar(c: Char): String =
        if (c.isLetterOrDigit() || c == '_') c.toString() else "\\$c"

    // ---- Verification ----

    /** The candidate must match, and capture exactly what was selected. */
    private fun capturesExactly(pattern: String, sample: String, selection: String): Boolean {
        val captured = ReminderExtractor.capture(pattern, sample) ?: return false
        return captured.trim() == selection
    }

    private fun matchesSomewhere(pattern: String, sample: String): Boolean =
        ReminderExtractor.compile(pattern)?.containsMatchIn(sample) == true

    // ---- Date formats ----

    /**
     * The java.time pattern for a selected date or time, when it is one of the shapes that actually
     * turn up in bank messages and parking tickets. Null otherwise - the extractor already falls
     * back to trying a list of common formats, so a wrong guess here would be worse than none.
     */
    fun dateFormatOf(selection: String): String? {
        val s = selection.trim()
        val date = Regex("""^(\d{2})([-/. ])(\d{2})\2(\d{4})$""").find(s)
        if (date != null) return "dd${date.groupValues[2]}MM${date.groupValues[2]}yyyy"
        val iso = Regex("""^(\d{4})([-/.])(\d{2})\2(\d{2})$""").find(s)
        if (iso != null) return "yyyy${iso.groupValues[2]}MM${iso.groupValues[2]}dd"
        val named = Regex("""^(\d{2})([-/. ])([A-Za-z]{3})\2(\d{4})$""").find(s)
        if (named != null) return "dd${named.groupValues[2]}MMM${named.groupValues[2]}yyyy"
        val namedShort = Regex("""^(\d{2})([-/. ])([A-Za-z]{3})\2(\d{2})$""").find(s)
        if (namedShort != null) return "dd${namedShort.groupValues[2]}MMM${namedShort.groupValues[2]}yy"
        if (Regex("""^\d{2}:\d{2}$""").matches(s)) return "HH:mm"
        // No year - common enough in bank SMS to be worth recognising rather than falling through.
        // ReminderPolicy fills the year in from the message date.
        val dayMonthNamed = Regex("""^(\d{2})([-/. ])([A-Za-z]{3})$""").find(s)
        if (dayMonthNamed != null) return "dd${dayMonthNamed.groupValues[2]}MMM"
        val dayMonth = Regex("""^(\d{2})([-/.])(\d{2})$""").find(s)
        if (dayMonth != null) return "dd${dayMonth.groupValues[2]}MM"
        // A date and a time together, e.g. "15/01/2026 14:30".
        val parts = s.split(Regex("""\s+"""))
        if (parts.size == 2) {
            val d = dateFormatOf(parts[0])
            val t = dateFormatOf(parts[1])
            if (d != null && t != null) return "$d $t"
        }
        return null
    }
}
