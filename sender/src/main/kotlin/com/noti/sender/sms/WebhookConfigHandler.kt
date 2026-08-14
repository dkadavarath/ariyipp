package com.noti.sender.sms

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage

/**
 * Decrypts a webhook-config push from Main and returns it - but only when the companion is set to
 * accept remote config (the kill-switch), the shared key matches, and the payload is actually a
 * config message. Otherwise null (no-op). [apply] overwrites the local webhook settings; the fields
 * stay editable, so a later local edit or a later push both win last.
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
        return wire as? WireMessage.WebhookConfig
    }

    fun apply(context: Context, cfg: WireMessage.WebhookConfig) {
        val s = SenderSettings.get(context)
        s.n8nEnabled = cfg.enabled
        s.n8nUrl = cfg.url
        s.n8nAuthHeaderName = cfg.authHeaderName
        s.n8nAuthHeaderPrefix = cfg.authHeaderPrefix
        s.n8nToken = cfg.authToken
    }
}
