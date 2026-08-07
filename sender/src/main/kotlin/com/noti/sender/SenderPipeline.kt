package com.noti.sender

import android.content.Context
import android.util.Log
import com.noti.shared.RelayMessage

/**
 * Entry point for a captured message. The next increment wires this to encrypt the message with the
 * pre-shared key and fan it out to two destinations — FcmSender.send() to noti's token, and a
 * plaintext POST to the n8n webhook. For now it just records receipt.
 *
 * Logs only the sender and length, never the body — this app handles OTPs.
 */
object SenderPipeline {

    private const val TAG = "noti-sender"

    fun handle(context: Context, message: RelayMessage) {
        Log.i(TAG, "SMS captured from '${message.title}' (${message.body.length} chars)")
        // TODO(next): encrypt with MessageCrypto → FcmSender.send() + plaintext n8n POST.
    }
}
