package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts an inbound push on the companion and returns it iff it's a liveness heartbeat from Main
 * (shared key matches, payload is a heartbeat). Otherwise null. Kept free of Firebase/engine types
 * so it's unit-testable; the caller records the beat and answers a force-check.
 */
object HeartbeatHandler {

    const val PAYLOAD_KEY = "payload"

    fun parse(context: Context, data: Map<String, String>): WireMessage.Heartbeat? {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val key = SenderSettings.get(context).relayKey.takeIf { it.isNotBlank() } ?: return null
        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            return null
        }
        val wire = try {
            Wire.decode(plaintext)
        } catch (e: Exception) {
            return null
        }
        return wire as? WireMessage.Heartbeat
    }
}
