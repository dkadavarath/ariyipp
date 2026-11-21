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

    /** Below the last accepted id by at most this much: a duplicate FCM delivery of a command
     *  already handled, dropped quietly. Further below: Main's ids restarted (reinstall, cleared
     *  data), which needs a re-pair and is reported. */
    private const val DUPLICATE_WINDOW = 50L

    /** What [check] made of a push. */
    sealed interface Outcome {
        /** Not a command for us at all (another type, wrong key, commands off). */
        object NotCommand : Outcome
        data class Accepted(val cmd: WireMessage.Command) : Outcome
        /** A genuine command (it decrypted) that won't be sent. [report] is false for a duplicate
         *  delivery: acking FAILED there would overwrite the ticks of the copy that was sent. */
        data class Rejected(val msgId: Long, val reason: String, val report: Boolean) : Outcome
    }

    fun parse(context: Context, data: Map<String, String>): WireMessage.Command? =
        (check(context, data) as? Outcome.Accepted)?.cmd

    fun check(context: Context, data: Map<String, String>, now: Long = System.currentTimeMillis()): Outcome {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return Outcome.NotCommand

        val settings = SenderSettings.get(context)
        if (!settings.acceptCommands) return Outcome.NotCommand
        val key = settings.relayKey.takeIf { it.isNotBlank() } ?: return Outcome.NotCommand

        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            return Outcome.NotCommand
        }
        val wire = try {
            Wire.decodeFrom(plaintext, com.noti.shared.Wire.Direction.MAIN_TO_COMPANION)
        } catch (e: Exception) {
            return Outcome.NotCommand
        }
        val cmd = (wire as? WireMessage.Command)?.takeIf { it.to.isNotBlank() } ?: return Outcome.NotCommand

        // Replay protection: msgId is Main's own Room autoincrement id, so a genuine command only
        // ever arrives with a higher value than the last one accepted. issuedAt additionally bounds
        // the replay window for a command that's never been seen yet (e.g. right after re-pairing,
        // when the high-water mark alone wouldn't help).
        if (cmd.msgId <= 0) return Outcome.Rejected(cmd.msgId, "no message id", report = false)
        val last = settings.lastCommandMsgId
        if (cmd.msgId <= last) {
            return if (last - cmd.msgId < DUPLICATE_WINDOW) {
                Outcome.Rejected(cmd.msgId, "already handled", report = false)
            } else {
                Outcome.Rejected(cmd.msgId, "message id ${cmd.msgId} is behind the last one seen ($last) - " +
                    "main was reinstalled or restored from a backup; on main, regenerate the key (Settings > Relay) and re-scan it here", report = true)
            }
        }
        if (cmd.issuedAt <= 0) return Outcome.Rejected(cmd.msgId, "no send time", report = true)
        val age = now - cmd.issuedAt
        if (age > MAX_AGE_MS) {
            return Outcome.Rejected(cmd.msgId, "arrived ${age / 60_000} min late (limit ${MAX_AGE_MS / 60_000}) - not sent", report = true)
        }
        if (-age > MAX_FUTURE_SKEW_MS) {
            return Outcome.Rejected(cmd.msgId, "the phones' clocks differ by ${-age / 60_000} min " +
                "(limit ${MAX_FUTURE_SKEW_MS / 60_000}) - set both to automatic time", report = true)
        }

        // Record before the caller initiates the SMS send, so a duplicate delivery of the same push
        // arriving while the first send is still in flight can't slip through.
        settings.lastCommandMsgId = cmd.msgId
        return Outcome.Accepted(cmd)
    }
}
