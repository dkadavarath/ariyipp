package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts and validates a "send this SMS" command pushed from the hub. Returns the command only when
 * commands are enabled, the shared key matches, and the payload is a well-formed command — otherwise
 * null, so a disabled/misconfigured device or a hostile/other-typed message is a no-op. Free of
 * Android telephony so it's testable; the actual send is [SmsSender].
 */
object SmsCommandHandler {

    const val PAYLOAD_KEY = "payload"

    fun parse(context: Context, data: Map<String, String>): WireMessage.Command? {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return null

        val settings = SenderSettings.get(context)
        if (!settings.acceptCommands) return null
        val key = settings.relayKey.takeIf { it.isNotBlank() } ?: return null

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
        return (wire as? WireMessage.Command)?.takeIf { it.to.isNotBlank() }
    }
}
