package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.Envelope
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts an inbound push on the companion and returns it iff it's Main announcing its push
 * endpoint (shared key matches, payload is a token). Otherwise null. Kept free of Firebase types so
 * it's unit-testable; the caller stores the endpoint as the relay target.
 */
object HubTokenHandler {

    fun parse(context: Context, data: Map<String, String>): WireMessage.Token? =
        Envelope.open(data, SenderSettings.get(context).relayKey, Wire.Direction.MAIN_TO_COMPANION)?.let { from(it.message) }

    /** The endpoint announcement [message] is, if it is one with an endpoint. */
    fun from(message: WireMessage): WireMessage.Token? = (message as? WireMessage.Token)?.takeIf { it.endpoint.isNotBlank() }
}
