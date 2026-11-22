package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.Envelope
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/** Decrypts an inbound push on the companion and returns it iff it's Main asking for a catch-up
 *  ([WireMessage.CatchUp]). Free of Firebase types so it's unit-testable. */
object CatchUpHandler {

    /** How far back a catch-up with no start (Main holds nothing yet) reaches. */
    const val DEFAULT_LOOKBACK_MS = 3 * 24 * 60 * 60_000L
    /** Never further back than this, whatever Main asks: bounds the work one push can cause. */
    const val MAX_LOOKBACK_MS = 30 * 24 * 60 * 60_000L
    /** A second catch-up request within this long of the last one acted on is ignored. FCM's
     *  onDeletedMessages can fire more than once for one gap, and a captured request replayed
     *  over and over would otherwise keep the companion repushing. */
    const val REPEAT_WINDOW_MS = 10 * 60_000L

    /** Whether a catch-up may run now, given when the last one did. Pure. */
    fun mayRun(lastAtMs: Long, now: Long): Boolean = now - lastAtMs !in 0 until REPEAT_WINDOW_MS

    fun parse(context: Context, data: Map<String, String>): WireMessage.CatchUp? =
        Envelope.open(data, SenderSettings.get(context).relayKey, Wire.Direction.MAIN_TO_COMPANION)?.let { from(it.message) }

    /** The catch-up request [message] is, if it is one. */
    fun from(message: WireMessage): WireMessage.CatchUp? = message as? WireMessage.CatchUp

    /** The received-time to re-send from, on this phone's clock. Pure. */
    fun startMs(request: WireMessage.CatchUp, now: Long): Long {
        val floor = now - MAX_LOOKBACK_MS
        val asked = if (request.sinceMs <= 0) now - DEFAULT_LOOKBACK_MS else request.sinceMs
        return asked.coerceIn(floor, now)
    }
}
