package com.noti.sender.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noti.sender.R
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import com.noti.shared.WebhookUrlPolicy
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts a webhook-config push from Main and returns it - but only when the companion is set to
 * accept remote config (the kill-switch, default OFF: this only takes effect once a user opts in
 * from Settings), the shared key matches, the payload is actually a config message, and any
 * non-blank url passes [WebhookUrlPolicy]. Otherwise null (no-op). [apply] overwrites the local
 * webhook settings and
 * posts a notification so a remote change is never silent; the fields stay editable, so a later
 * local edit or a later push both win last.
 */
object WebhookConfigHandler {

    const val PAYLOAD_KEY = "payload"

    fun parse(context: Context, data: Map<String, String>): WireMessage.WebhookConfig? {
        val ciphertext = data[PAYLOAD_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val s = SenderSettings.get(context)
        if (!s.acceptRemoteConfig) return null
        val key = s.relayKey.takeIf { it.isNotBlank() } ?: return null
        val plaintext = try {
            MessageCrypto.decrypt(ciphertext, key)
        } catch (e: Exception) {
            return null
        }
        val wire = try {
            Wire.decode(plaintext)
        } catch (e: Exception) {
            return null
        }
        val cfg = wire as? WireMessage.WebhookConfig ?: return null
        // Reject a stale/replayed config: only a version newer than the last one applied can take
        // effect, so a captured older push can't roll back to a stale URL or auth token.
        if (cfg.configVersion <= s.lastWebhookConfigVersion) return null
        // A blank url just means "disabled" (see NotiConfigSender.pushWebhook); anything else must
        // pass the same https/loopback policy Uploader/WebhookPoster enforce, so a compromised or
        // replayed push can't silently redirect relayed SMS (including OTPs) to a
        // plaintext-readable endpoint of the attacker's choosing.
        if (cfg.url.isNotBlank() && !WebhookUrlPolicy.isAllowed(cfg.url)) return null
        return cfg
    }

    fun apply(context: Context, cfg: WireMessage.WebhookConfig) {
        val s = SenderSettings.get(context)
        s.n8nEnabled = cfg.enabled
        s.n8nUrl = cfg.url
        s.n8nAuthHeaderName = cfg.authHeaderName
        s.n8nAuthHeaderPrefix = cfg.authHeaderPrefix
        s.n8nToken = cfg.authToken
        s.lastWebhookConfigVersion = cfg.configVersion
        RemoteConfigNotice.notify(context, cfg)
    }
}

/** Visible signal that Main just changed this device's webhook config - the only other trace is a
 *  Diag log line, which nobody sees in real time. Not an approval gate (the change already took
 *  effect by the time this posts); it just makes an otherwise-silent remote change noticeable. */
private object RemoteConfigNotice {

    private const val CHANNEL = "webhook_config_pushed"
    private const val ID = 4712

    fun notify(context: Context, cfg: WireMessage.WebhookConfig) {
        ensureChannel(context)
        val text = if (cfg.enabled) {
            context.getString(R.string.webhook_config_pushed_enabled, cfg.url)
        } else {
            context.getString(R.string.webhook_config_pushed_disabled)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.webhook_config_pushed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            // No-ops if POST_NOTIFICATIONS is not granted (API 33+); guard against SecurityException.
            NotificationManagerCompat.from(context).notify(ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.webhook_config_pushed_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }
}
