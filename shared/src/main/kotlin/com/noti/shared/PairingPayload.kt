package com.noti.shared

/**
 * Compact pairing strings carried in a QR (and copyable as text). Two directions, each with its own
 * prefix so a scanner can't confuse them:
 *  - Forward (`noti-pair:v1:`): ippu's FCM token + the shared AES key. Shown on ippu, scanned by ariy.
 *  - Reverse (`ariy-pair:v1:`): ariy's FCM token + the shared key it holds. Shown on ariy, scanned by
 *    ippu so it can push send-SMS commands back.
 *
 * Body format is `<token>|<keyBase64>`. Neither an FCM token nor standard base64 contains '|', so
 * it's an unambiguous separator. Kept pure so the format is unit-tested on both sides.
 */
object PairingPayload {

    private const val PREFIX = "noti-pair:v1:"
    private const val REVERSE_PREFIX = "ariy-pair:v1:"

    fun format(token: String, keyBase64: String): String = PREFIX + body(token, keyBase64)

    /** Returns (token, keyBase64), or null if [text] isn't a well-formed forward pairing string. */
    fun parse(text: String): Pair<String, String>? = parseBody(text, PREFIX)

    fun formatReverse(token: String, keyBase64: String): String = REVERSE_PREFIX + body(token, keyBase64)

    /** Returns (token, keyBase64), or null if [text] isn't a well-formed reverse pairing string. */
    fun parseReverse(text: String): Pair<String, String>? = parseBody(text, REVERSE_PREFIX)

    private fun body(token: String, keyBase64: String) = "$token|$keyBase64"

    private fun parseBody(text: String, prefix: String): Pair<String, String>? {
        if (!text.startsWith(prefix)) return null
        val rest = text.removePrefix(prefix)
        val sep = rest.indexOf('|')
        if (sep <= 0 || sep >= rest.length - 1) return null
        return rest.substring(0, sep) to rest.substring(sep + 1)
    }
}
