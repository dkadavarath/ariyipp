package com.noti.logger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noti.logger.config.Settings

/**
 * Receives FCM data messages from the sender app and hands them to [PushMessageHandler] (decrypt →
 * notify). Deliberately thin — all logic lives in the handler, which is testable without Google
 * Play Services; this class is just the FCM entry point, which GPS delivers to.
 */
class NotiMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        PushMessageHandler.handle(applicationContext, message.data)
    }

    /** Fired on (re)registration; cache the token so the pairing screen can show it. */
    override fun onNewToken(token: String) {
        Settings.get(applicationContext).fcmToken = token
    }
}
