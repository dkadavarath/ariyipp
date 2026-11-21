package com.noti.logger.reminder

import java.time.LocalDate

/**
 * Drafts a bill rule from one example message: which phrase identifies it, which number is the bill,
 * its currency and its due date. A starting point the user overrides, not a verdict.
 *
 * It does not write regexes itself. It only decides *which span* is the amount, the date and so on,
 * then hands that span to [PatternBuilder] - the same path as selecting the text by hand - so every
 * suggested pattern has already been verified to capture exactly that span from this message. A
 * guess that wouldn't survive that check is dropped and the field is left empty, which is honest in
 * a way a plausible wrong pattern is not.
 *
 * Choosing the span is a scoring pass over candidates, because bank SMS routinely carry several
 * numbers and dates: a total and a minimum due, a statement date and a due date, a card number.
 * Words just before a candidate ("total", "due by") pull it up; others ("minimum", "limit") push it
 * down. Ties go to the earliest candidate.
 */
object RuleAutoParser {

    data class Draft(
        val matchPattern: String?,
        val amountPattern: String?,
        val currencyPattern: String?,
        val datePattern: String?,
        val dateFormat: String?,
    ) {
        val isEmpty get() = listOf(matchPattern, amountPattern, currencyPattern, datePattern).all { it == null }
    }

    private const val CURRENCY = """Rs\.?|INR|₹|AED|Dhs?\.?|USD|US\$|\$|EUR|€|GBP|£|SAR|QAR|KWD|OMR|BHD"""
    private const val NUMBER = """\d[\d,]*(?:\.\d+)?"""

    /** Currency before the number ("Rs 18,402.75") or after it ("412.50 AED"). */
    private val currencyFirst = Regex("""(?<![A-Za-z])($CURRENCY)\s*($NUMBER)""", RegexOption.IGNORE_CASE)
    private val numberFirst = Regex("""($NUMBER)\s*(AED|INR|USD|EUR|GBP|SAR|QAR|KWD|OMR|BHD)(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    /** The date shapes bank messages actually use. Each candidate must also parse before it counts. */
    private val dateShapes = listOf(
        Regex("""\b\d{1,2}([-/. ])(?:\d{1,2}|[A-Za-z]{3,9})\1\d{2,4}\b"""), // 06-OCT-26, 15/11/2026, 7 Feb 2026
        Regex("""\b\d{4}-\d{2}-\d{2}\b"""),                                  // 2026-11-15
        Regex("""\b[A-Za-z]{3,9} \d{1,2}, \d{4}\b"""),                        // Nov 15, 2026
        Regex("""\b\d{1,2}[- ][A-Za-z]{3}\b"""),                             // 07-Feb (year implied)
    )

    private val amountBoost = listOf("total", "amount due", "amt due", "outstanding", "bill", "balance", "payable", "due")
    private val amountPenalty = listOf("min", "limit", "available", "avl", "credited", "received", "paid", "cashback", "reward")
    private val dateBoost = listOf("due", "by", "before", "pay", "last date")
    private val datePenalty = listOf("statement date", "generated", "dated", "since")

    /** Phrases that say "this is a bill", most specific first. The first one present identifies it. */
    private val identifyPhrases = listOf(
        "statement", "total amount due", "amount due", "bill", "payment due", "due",
    )

    /**
     * The sender filter for a rule drafted from one message. Operator-routed sender IDs (India's
     * "AD-ICICIB", "VM-ICICIB-S") change their prefix - and lately a suffix - from message to message,
     * so the stable core is what has to be matched; the filter is a substring test, so "ICICIB" still
     * matches every variant. Anything else (a phone number, "DEWA") is kept as it is.
     */
    fun senderFilter(sender: String): String {
        val s = sender.trim()
        return Regex("""^[A-Z]{2}-([A-Z0-9]{3,})(?:-[A-Z])?$""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1) ?: s
    }

    fun draft(sample: String, today: LocalDate = LocalDate.now()): Draft {
        val amount = bestAmount(sample)
        val amountPattern = amount?.let { PatternBuilder.forSelection(sample, it.numStart, it.numEnd, PatternBuilder.Field.AMOUNT, preferAnchor = true)?.pattern }
        val currencyPattern = amount?.let {
            PatternBuilder.forSelection(sample, it.curStart, it.curEnd, PatternBuilder.Field.CURRENCY)?.pattern
        }
        val date = bestDate(sample, today)
        val dateResult = date?.let { PatternBuilder.forSelection(sample, it.first, it.last + 1, PatternBuilder.Field.DATE, preferAnchor = true) }
        val matchPattern = identifyPhrases.firstNotNullOfOrNull { phrase ->
            val at = sample.indexOf(phrase, ignoreCase = true)
            if (at < 0) null
            else PatternBuilder.forSelection(sample, at, at + phrase.length, PatternBuilder.Field.IDENTIFY)?.pattern
        }
        return Draft(
            matchPattern = matchPattern,
            amountPattern = amountPattern,
            currencyPattern = currencyPattern,
            datePattern = dateResult?.pattern,
            dateFormat = dateResult?.dateFormat,
        )
    }

    // ---- Payment confirmations ----

    data class PaymentDraft(val matchPattern: String?, val amountPattern: String?)

    /** Phrases that say "this is a payment going through", most specific first. */
    private val paymentPhrases = listOf(
        "payment of", "payment received", "received a payment", "thank you for your payment",
        "has been received", "received", "paid", "debited", "payment",
    )
    private val paidBoost = listOf("payment", "received", "paid", "debited", "of")
    private val paidPenalty = listOf("min", "outstanding", "balance", "due", "limit", "available", "avl")

    /**
     * Drafts the payment side of a rule from one confirmation message: a phrase that identifies it
     * and the paid amount. On a payment message the amount *after* the payment ("outstanding",
     * "available limit") is the trap, so those cues count against a candidate.
     */
    fun draftPayment(sample: String): PaymentDraft {
        val amount = bestAmount(sample, paidBoost, paidPenalty)
        val amountPattern = amount?.let { PatternBuilder.forSelection(sample, it.numStart, it.numEnd, PatternBuilder.Field.AMOUNT, preferAnchor = true)?.pattern }
        val matchPattern = paymentPhrases.firstNotNullOfOrNull { phrase ->
            val at = sample.indexOf(phrase, ignoreCase = true)
            if (at < 0) null
            else PatternBuilder.forSelection(sample, at, at + phrase.length, PatternBuilder.Field.IDENTIFY)?.pattern
        }
        return PaymentDraft(matchPattern, amountPattern)
    }

    // ---- Amount ----

    private class AmountCandidate(val numStart: Int, val numEnd: Int, val curStart: Int, val curEnd: Int, val score: Int)

    private fun bestAmount(
        sample: String,
        boost: List<String> = amountBoost,
        penalty: List<String> = amountPenalty,
    ): AmountCandidate? {
        val found = ArrayList<AmountCandidate>()
        for (m in currencyFirst.findAll(sample)) {
            val cur = m.groups[1]!!.range
            val num = m.groups[2]!!.range
            found += AmountCandidate(num.first, num.last + 1, cur.first, cur.last + 1, score(sample, cur.first, boost, penalty))
        }
        for (m in numberFirst.findAll(sample)) {
            val num = m.groups[1]!!.range
            val cur = m.groups[2]!!.range
            if (found.any { num.first in it.numStart until it.numEnd }) continue
            found += AmountCandidate(num.first, num.last + 1, cur.first, cur.last + 1, score(sample, num.first, boost, penalty))
        }
        // Highest score; among equals the earliest, because the headline figure usually comes first.
        return found.sortedBy { it.numStart }.maxByOrNull { it.score }
    }

    // ---- Date ----

    private fun bestDate(sample: String, today: LocalDate): IntRange? {
        val found = ArrayList<Pair<IntRange, Int>>()
        for (shape in dateShapes) {
            for (m in shape.findAll(sample)) {
                if (found.any { it.first.first <= m.range.last && m.range.first <= it.first.last }) continue
                if (ReminderPolicy.parseDate(m.value, null, today) == null) continue
                found += m.range to score(sample, m.range.first, dateBoost, datePenalty)
            }
        }
        return found.sortedBy { it.first.first }.maxByOrNull { it.second }?.first
    }

    // ---- Scoring ----

    /** Looks at the few words before [at]: each boosting cue adds, each penalising one subtracts more
     *  (a "minimum due" must lose to a "total" even though both say "due"). */
    private fun score(sample: String, at: Int, boost: List<String>, penalty: List<String>): Int {
        val before = sample.substring(maxOf(0, at - 28), at).lowercase()
        return boost.count { it in before } - 3 * penalty.count { it in before }
    }
}
