package com.noti.shared

import kotlinx.serialization.Serializable

/**
 * A "send this SMS" command carried (encrypted) from noti to sndi: sndi sends [body] to [to] via the
 * SIM. Shared so both sides serialize the identical shape. Travels in the same encrypted FCM data
 * field as [RelayMessage] does the other way — each app only ever receives its own kind.
 *
 * [sim] is the SIM slot index to send from (0 = first, 1 = second); -1 means "let sndi pick its
 * default SIM". Defaulted so older senders that omit it still deserialize.
 */
@Serializable
data class SendCommand(val to: String, val body: String, val sim: Int = -1)
