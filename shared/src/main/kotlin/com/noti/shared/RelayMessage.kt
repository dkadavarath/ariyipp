package com.noti.shared

import kotlinx.serialization.Serializable

/**
 * The wire format carried (encrypted) between the sender app and noti: what to show as the
 * notification's title and body. Shared so both sides serialize/deserialize the identical shape.
 */
@Serializable
data class RelayMessage(val title: String = "", val body: String = "")
