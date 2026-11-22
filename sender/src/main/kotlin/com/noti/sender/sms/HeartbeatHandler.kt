package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.Envelope
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts an inbound push on the companion and returns it iff it's a liveness heartbeat from Main
 * (shared key matches, payload is a heartbeat). Otherwise null. Kept free of Firebase/engine types
 * so it's unit-testable; the caller records the beat and answers a force-check.
 */
object HeartbeatHandler {

    const val PAYLOAD_KEY = com.noti.shared.Envelope.PAYLOAD_KEY

    fun parse(context: Context, data: Map<String, String>): WireMessage.Heartbeat? =
        Envelope.open(data, SenderSettings.get(context).relayKey, Wire.Direction.MAIN_TO_COMPANION)?.let { from(it.message) }

    /** The heartbeat [message] is, if it is one. */
    fun from(message: WireMessage): WireMessage.Heartbeat? = message as? WireMessage.Heartbeat
}
