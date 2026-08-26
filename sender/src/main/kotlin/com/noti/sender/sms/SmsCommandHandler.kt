package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts and validates a "send this SMS" command pushed from the hub. Returns the command only when
 * commands are enabled, the shared key matches, and the payload is a well-formed command - otherwise
 * null, so a disabled/misconfigured device or a hostile/other-typed message is a no-op. Free of
 * Android telephony so it's testable; the actual send is [SmsSender].
 */
object SmsCommandHandler {

    const val PAYLOAD_KEY = "payload"

    /** A command older than this - or claiming to be issued this far in the future - is rejected.
     *  Wide enough to tolerate real FCM/Doze delivery delay, narrow enough to bound how long a
     *  captured ciphertext stays replayable even before [SenderSettings.lastCommandMsgId] alone
     *  would reject it. */
    private const val MAX_AGE_MS = 10 * 60_000L
    private const val MAX_FUTURE_SKEW_MS = 2 * 60_000L

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
        val cmd = (wire as? WireMessage.Command)?.takeIf { it.to.isNotBlank() } ?: return null

        // Replay protection: msgId is Main's own Room autoincrement id, so a genuine command only
        // ever arrives with a higher value than the last one accepted. issuedAt additionally bounds
        // the replay window for a command that's never been seen yet (e.g. right after re-pairing,
        // when the high-water mark alone wouldn't help).
        if (cmd.msgId <= 0 || cmd.msgId <= settings.lastCommandMsgId) return null
        val now = System.currentTimeMillis()
        if (cmd.issuedAt <= 0 || now - cmd.issuedAt > MAX_AGE_MS || cmd.issuedAt - now > MAX_FUTURE_SKEW_MS) return null

        // Record before the caller initiates the SMS send, so a duplicate delivery of the same push
        // arriving while the first send is still in flight can't slip through.
        settings.lastCommandMsgId = cmd.msgId
        return cmd
    }
}
