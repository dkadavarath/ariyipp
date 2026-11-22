package com.noti.logger.redact

/**
 * Masks what a kept notification shouldn't carry on into the database and the webhook: one-time
 * codes and full card numbers. Narrow on purpose, because reminder rules read the stored text: an
 * amount, a due date or a card's last four digits must come through untouched.
 *
 * - A one-time code is a 4-8 digit run shortly after an unmistakable OTP phrase ("OTP is 482913",
 *   "verification code: 4829"), as whole words. Narrower than the "Copy code" action's test: a
 *   bill's "PIN"/"code" wording must not take the amount after it, and nothing with a currency
 *   marker in between ("code ... Rs 2450") is ever masked. A bare number elsewhere is never touched.
 * - A card number is 13-19 digits (spaces or dashes between groups allowed) that pass the Luhn
 *   check. It keeps its last four, so "card ending 1234" rules still match.
 */
object SensitiveMask {

    private const val DOTS = "••••"
    /** How far after an OTP phrase its code may be stated (characters). */
    private const val NEAR_PHRASE = 60

    private val code = Regex(
        "(\\b(?:otp|one[- ]?time (?:password|passcode|code|pin)|verification code|security code|passcode|" +
            "auth(?:entication)? code|login code|code is|pin is)\\b([^0-9\\n]{0,20}))(?<!\\d)\\d{4,8}(?!\\d|[.,]\\d)",
        RegexOption.IGNORE_CASE,
    )
    /** The code stated after the amount ("OTP for Rs 2450.00 is 918273"): in a message that has an
     *  OTP phrase, a 4-8 digit run right after "is" or ":", without a currency word just before. */
    private val otpPhrase = Regex(
        "\\b(?:otp|one[- ]?time (?:password|passcode|code|pin)|verification code|security code|passcode)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val afterIs = Regex("(\\b(?:is|:)\\s{0,3})(?<!\\d)\\d{4,8}(?!\\d|[.,]\\d)", RegexOption.IGNORE_CASE)
    private val currency = Regex("(?i)(?:\\brs\\b|\\binr\\b|₹|\\$|€|£|\\baed\\b|\\busd\\b|\\beur\\b|amount|amt)")
    private val cardLike = Regex("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])")

    fun apply(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        var foundCode = false
        var withoutCodes = code.replace(text) { m ->
            if (currency.containsMatchIn(m.groupValues[2])) m.value else { foundCode = true; m.groupValues[1] + DOTS }
        }
        // The fallback, for a code not right after its phrase ("OTP for Rs 2450.00 is 918273"):
        // only when the first pass found none, and only shortly after the phrase.
        val phraseEnds = if (foundCode) emptyList() else otpPhrase.findAll(withoutCodes).map { it.range.last }.toList()
        if (phraseEnds.isNotEmpty()) {
            val src = withoutCodes
            withoutCodes = afterIs.replace(src) { m ->
                // Only the code that phrase is about: one stated shortly after it. A balance or a
                // reference later in the same message ("... balance is 12500") is left alone.
                if (phraseEnds.none { m.range.first - it in 0..NEAR_PHRASE }) return@replace m.value
                // The words between the previous number and this one: "Amount is 2450" is an amount;
                // in "Rs 2450.00 is 918273" the Rs belongs to the number before.
                val before = src.substring(maxOf(0, m.range.first - 20), m.range.first)
                val gap = before.substring(before.indexOfLast { it.isDigit() } + 1)
                if (currency.containsMatchIn(gap + m.groupValues[1])) m.value else m.groupValues[1] + DOTS
            }
        }
        return cardLike.replace(withoutCodes) { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && luhn(digits)) "$DOTS ${digits.takeLast(4)}" else m.value
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
