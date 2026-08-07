package com.noti.sender.sms

import com.noti.shared.RelayMessage

/** One part of a received SMS: the originating address and the body text of that part. */
data class SmsPart(val from: String?, val body: String)

/**
 * Combines the parts of a single received SMS into one [RelayMessage]. A long (multipart) SMS arrives
 * as several parts in one broadcast; their bodies are concatenated in arrival order and the sender is
 * taken from the first part that has one. The sender becomes the notification title, the text the
 * body. Returns null when there is nothing worth relaying.
 *
 * Pure and Android-free so it can be unit-tested off-device.
 */
object SmsAssembler {

    fun assemble(parts: List<SmsPart>): RelayMessage? {
        if (parts.isEmpty()) return null
        val body = parts.joinToString("") { it.body }
        if (body.isBlank()) return null
        val from = parts.firstNotNullOfOrNull { it.from?.trim()?.takeIf(String::isNotEmpty) } ?: "Unknown"
        return RelayMessage(title = from, body = body)
    }
}
