package com.noti.shared

/**
 * Analytics labels for every FCM send, as `<type>-<direction>`. Both roles are the same Firebase
 * app, so without the direction a delivery report can't tell Main → companion from companion → Main
 * - which is exactly the split a one-way failure needs. Labels show up in the console's Messaging
 * reports and in the BigQuery delivery export (`analytics_label`).
 */
object FcmLabel {
    const val HEARTBEAT_TO_COMPANION = "heartbeat-m2c"
    const val HEARTBEAT_TO_MAIN = "heartbeat-c2m"
    const val COMMAND = "command-m2c"
    const val CATCH_UP = "catchup-m2c"
    const val STATUS_REQUEST = "statusreq-m2c"
    const val CONFIG = "config-m2c"
    const val TOKEN_TO_COMPANION = "token-m2c"
    const val TOKEN_TO_MAIN = "token-c2m"
    const val RELAY = "relay-c2m"
    const val ACK = "ack-c2m"
    const val STATUS_REPORT = "status-c2m"

    /** The message id FCM returned ("projects/p/messages/0:123%abc" → "0:123%abc"), which is the
     *  `message_id` column of the BigQuery export - log it to find one send there. */
    fun messageId(detail: String): String = detail.substringAfterLast("/messages/", "")
}
