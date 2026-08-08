package com.noti.sender.sms

/** One part of a received SMS: originating address, body text, and the SMSC (sent) timestamp. */
data class SmsPart(val from: String?, val body: String, val sentMillis: Long)

/**
 * A fully-assembled inbound SMS: sender, body, the sent (SMSC) and received (on-device) timestamps,
 * and which SIM received it (e.g. "sim1").
 */
data class CapturedSms(
    val from: String,
    val body: String,
    val sentMillis: Long,
    val receivedMillis: Long,
    val sim: String,
)

/**
 * Combines the parts of a single received SMS. A long (multipart) SMS arrives as several parts in
 * one broadcast; their bodies are concatenated in arrival order, the sender and sent-time come from
 * the first part, and [receivedMillis]/[sim] are supplied by the caller (they come from the
 * broadcast, not the message parts). Returns null when there is nothing worth relaying.
 *
 * Pure and Android-free so it can be unit-tested off-device.
 */
object SmsAssembler {

    fun assemble(parts: List<SmsPart>, receivedMillis: Long, sim: String): CapturedSms? {
        if (parts.isEmpty()) return null
        val body = parts.joinToString("") { it.body }
        if (body.isBlank()) return null
        val from = parts.firstNotNullOfOrNull { it.from?.trim()?.takeIf(String::isNotEmpty) } ?: "Unknown"
        return CapturedSms(
            from = from,
            body = body,
            sentMillis = parts.first().sentMillis,
            receivedMillis = receivedMillis,
            sim = sim,
        )
    }
}
