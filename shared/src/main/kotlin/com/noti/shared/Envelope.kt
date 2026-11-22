package com.noti.shared

/**
 * A push between the paired phones: a [WireMessage], tagged with its direction and stamped with its
 * send time (Wire.encodeFor), encrypted with the shared key, carried in the FCM data field
 * [PAYLOAD_KEY]. [seal] and [open] are the two ends; everything that sends or reads one goes
 * through them, rather than repeating the encrypt-and-encode / decrypt-and-check steps.
 */
object Envelope {

    const val PAYLOAD_KEY = "payload"

    /** What a push said, and when (the sender's clock, see ReplayGuard; 0 if unstamped). */
    data class Opened(val message: WireMessage, val sentAt: Long)

    /** The encrypted payload for [message] travelling [direction]. */
    fun seal(message: WireMessage, direction: Wire.Direction, keyBase64: String): String =
        MessageCrypto.encrypt(Wire.encodeFor(message, direction), keyBase64)

    /** The FCM data map carrying [message]. */
    fun data(message: WireMessage, direction: Wire.Direction, keyBase64: String): Map<String, String> =
        mapOf(PAYLOAD_KEY to seal(message, direction, keyBase64))

    /** Why a push couldn't be opened, for callers that report it. */
    enum class Failure { NO_PAYLOAD, NO_KEY, WRONG_KEY, UNTAGGED, WRONG_DIRECTION, MALFORMED }

    sealed interface Result {
        data class Ok(val opened: Opened) : Result
        data class Failed(val why: Failure) : Result
    }

    /** Decrypts and checks the push in [data], which must be travelling [expected]. */
    fun openOrWhy(data: Map<String, String>, keyBase64: String, expected: Wire.Direction): Result {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return Result.Failed(Failure.NO_PAYLOAD)
        if (keyBase64.isBlank()) return Result.Failed(Failure.NO_KEY)
        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, keyBase64)
        } catch (e: Exception) {
            return Result.Failed(Failure.WRONG_KEY)
        }
        val message = try {
            Wire.decodeFrom(plaintext, expected)
        } catch (e: Wire.UntaggedException) {
            return Result.Failed(Failure.UNTAGGED)
        } catch (e: Wire.WrongDirectionException) {
            return Result.Failed(Failure.WRONG_DIRECTION)
        } catch (e: Exception) {
            return Result.Failed(Failure.MALFORMED)
        }
        return Result.Ok(Opened(message, Wire.sentAt(plaintext)))
    }

    /** [openOrWhy] for callers that only care whether it opened. */
    fun open(data: Map<String, String>, keyBase64: String, expected: Wire.Direction): Opened? =
        (openOrWhy(data, keyBase64, expected) as? Result.Ok)?.opened
}
