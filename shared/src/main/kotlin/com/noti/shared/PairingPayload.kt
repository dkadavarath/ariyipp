package com.noti.shared

/**
 * Compact pairing string carried in the QR (and copyable as text): noti's FCM token plus the shared
 * AES key. Encoded on noti, decoded on sendi. Kept pure so the format is unit-tested on both sides.
 *
 * Format: `noti-pair:v1:<token>|<keyBase64>`. Neither an FCM token nor standard base64 contains '|',
 * so it's an unambiguous separator.
 */
object PairingPayload {

    private const val PREFIX = "noti-pair:v1:"

    fun format(token: String, keyBase64: String): String = "$PREFIX$token|$keyBase64"

    /** Returns (token, keyBase64), or null if [text] isn't a well-formed pairing string. */
    fun parse(text: String): Pair<String, String>? {
        if (!text.startsWith(PREFIX)) return null
        val rest = text.removePrefix(PREFIX)
        val sep = rest.indexOf('|')
        if (sep <= 0 || sep >= rest.length - 1) return null
        return rest.substring(0, sep) to rest.substring(sep + 1)
    }
}
