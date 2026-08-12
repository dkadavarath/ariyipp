package com.noti.logger.util

/**
 * Pulls a one-time code out of a message so a "Copy code" action can be offered on the notification.
 * Conservative on purpose: it takes a 4–8 digit code that sits right after an OTP-ish keyword, and
 * only falls back to a bare number when the message otherwise reads like a verification message —
 * so amounts, phone numbers, and reference IDs in ordinary texts don't get treated as codes.
 */
object OtpExtractor {

    private const val KEYWORDS = "otp|code|verification|verify|passcode|password|pin|one[- ]?time|auth"
    // A keyword, then up to 20 non-digits, then a standalone 4–8 digit run.
    private val nearKeyword = Regex("(?:$KEYWORDS)[^0-9]{0,20}(?<!\\d)(\\d{4,8})(?!\\d)", RegexOption.IGNORE_CASE)
    private val hasKeyword = Regex(KEYWORDS, RegexOption.IGNORE_CASE)
    private val standalone = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    fun extract(body: String): String? {
        nearKeyword.find(body)?.let { return it.groupValues[1] }
        if (hasKeyword.containsMatchIn(body)) standalone.find(body)?.let { return it.groupValues[1] }
        return null
    }
}
