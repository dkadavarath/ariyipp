package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts an inbound push on the companion and returns it iff it's Main announcing its push
 * endpoint (shared key matches, payload is a token). Otherwise null. Kept free of Firebase types so
 * it's unit-testable; the caller stores the endpoint as the relay target.
 */
object HubTokenHandler {

    fun parse(context: Context, data: Map<String, String>): WireMessage.Token? {
        val ciphertext = data[HeartbeatHandler.PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val key = SenderSettings.get(context).relayKey.takeIf { it.isNotBlank() } ?: return null
        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            return null
        }
        val wire = try {
            Wire.decodeFrom(plaintext, com.noti.shared.Wire.Direction.MAIN_TO_COMPANION)
        } catch (e: Exception) {
            return null
        }
        return (wire as? WireMessage.Token)?.takeIf { it.endpoint.isNotBlank() }
    }
}
