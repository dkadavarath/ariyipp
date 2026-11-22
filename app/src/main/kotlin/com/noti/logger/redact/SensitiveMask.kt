package com.noti.logger.redact

/**
 * Masks what a captured notification shouldn't carry off the phone: one-time codes and full card
 * numbers. Applied to the webhook upload only (see toUploadItem) - the stored copy, which reminder
 * rules read, is left as captured. So this can afford to over-mask an OTP message; what it must not
 * do is miss the code, or destroy what identifies the card.
 *
 * - In a message that reads like a one-time code (an OTP-type word, as a whole word), every code-
 *   like number is masked: 4-8 digits, a split "123 456", a prefixed "G-482913" (the prefix stays),
 *   before or after the word. Left alone: a card or account tail ("ending 1234", "XX1234",
 *   "a/c 1234"), an amount (a currency next to it, or decimals), dates and times.
 * - Anywhere, a full card number (13-19 digits, spaces or dashes allowed, Luhn-valid) keeps only
 *   its last four.
 */
object SensitiveMask {

    private const val DOTS = "••••"

    private val otpWords = Regex(
        "(?i)\\b(otp|one[- ]?time (?:password|passcode|code|pin)|verification|verify|security code|passcode|" +
            "password|pin|auth(?:entication)?|login code|code)\\b",
    )

    /** A code-like number: an optional letters-and-dash prefix, then 4-8 digits or a split 3+3.
     *  Not touching a date/time separator, a decimal or other digits. */
    private val codeToken = Regex(
        "(?<![\\w.,/:])((?:[A-Za-z]{1,3}-)?)(\\d{3}[ -]\\d{3}|\\d{4,8})(?![\\w/:]|[.,]\\d)",
    )

    /** What, just before a number, makes it a card/account tail or an amount rather than a code. */
    private val keepBefore = Regex(
        "(?i)(•{4}|ending(?: in| with)?|x{2,}|\\*{2,}|a/c(?: no)?|acc(?:oun)?t(?: no)?|card(?: no)?|" +
            "rs\\.?|inr|₹|\\$|€|£|aed|usd|eur|amount|amt)[\\s.:#-]*$",
    )

    private val cardLike = Regex("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])")

    fun apply(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        val withoutCards = cardLike.replace(text) { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && luhn(digits)) "$DOTS ${digits.takeLast(4)}" else m.value
        }
        if (!otpWords.containsMatchIn(withoutCards)) return withoutCards
        return codeToken.replace(withoutCards) { m ->
            val before = withoutCards.substring(maxOf(0, m.range.first - 16), m.range.first)
            if (keepBefore.containsMatchIn(before)) m.value else m.groupValues[1] + DOTS
        }
    }

    internal fun luhn(digits: String): Boolean {
        var sum = 0
        for ((i, ch) in digits.reversed().withIndex()) {
            var d = ch - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }
}
