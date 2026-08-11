package com.noti.shared

import kotlinx.serialization.Serializable

/**
 * The wire format carried (encrypted) between the sender app and noti: what to show as the
 * notification's title and body. Shared so both sides serialize/deserialize the identical shape.
 *
 * [dedupe] is a stable content key (sender|body|sentTime) the sender sets so the receiver can drop a
 * duplicate — the same SMS can arrive both via the live relay and via the missed-SMS sync.
 *
 * [time] is the SMS's real receive time on the sender phone (epoch ms). The receiver orders/displays
 * by this rather than push-arrival time, so a delayed or synced message lands in the right place in
 * the timeline. Both defaulted so older senders that omit them still deserialize.
 */
@Serializable
data class RelayMessage(
    val title: String = "",
    val body: String = "",
    val dedupe: String = "",
    val time: Long = 0,
)
