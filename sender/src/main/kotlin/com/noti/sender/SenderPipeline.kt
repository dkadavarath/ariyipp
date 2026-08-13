package com.noti.sender

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.Diag
import com.noti.shared.FcmSender
import com.noti.sender.net.WebhookPoster
import com.noti.sender.sms.CapturedSms
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import com.noti.shared.UploadBatch
import com.noti.shared.UploadItem
import com.noti.shared.epochMillisToIso

/**
 * Builds and sends a captured SMS to its two destinations, each an independent leg:
 *  - ippu, over encrypted FCM: a WireMessage.Relay (sender → title, body → text) that ippu shows.
 *  - the n8n webhook, plaintext: noti's schema with a structured From/Message/Sent/Received block.
 *
 * Each leg is its own idempotent call so the relay scan can advance a separate high-water mark per
 * leg. Logs only metadata (sender, length, HTTP codes) — never the body, which may be an OTP.
 */
object SenderPipeline {

    // Reuse one FcmSender so its cached OAuth token (valid ~1h) is kept, instead of re-parsing the
    // key, re-signing a JWT, and re-doing the token exchange on every SMS. Rebuilt only when the
    // service-account key changes. Guarded because sends can overlap across worker threads.
    @Volatile private var cachedSender: FcmSender? = null
    @Volatile private var cachedSenderKey: String = ""

    @Synchronized
    private fun fcmSender(serviceAccountJson: String): FcmSender {
        cachedSender?.let { if (cachedSenderKey == serviceAccountJson) return it }
        return FcmSender(serviceAccountJson).also {
            cachedSender = it
            cachedSenderKey = serviceAccountJson
        }
    }

    /** The encrypted FCM data payload for ippu: AES-GCM over the serialized wire message. */
    fun encryptForFcm(message: WireMessage, keyBase64: String): String =
        MessageCrypto.encrypt(Wire.encode(message), keyBase64)

    /** What ippu shows: sender "on <sim>" as the title (e.g. "+971500000000 on e&"), body verbatim. */
    fun fcmMessage(sms: CapturedSms): WireMessage.Relay =
        WireMessage.Relay(
            title = if (sms.sim.isNotBlank()) "${sms.from} on ${sms.sim}" else sms.from,
            body = sms.body,
            dedupe = dedupeKey(sms),
            time = sms.receivedMillis,
        )

    /** Content key ippu uses as a secondary dedup (e.g. against the full repush). */
    fun dedupeKey(sms: CapturedSms): String {
        val raw = "${sms.from}|${sms.body}|${sms.sentMillis}"
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
    }

    /** True when ariy has everything needed to push a relay to ippu (used to gate the FCM leg). */
    fun isConfigured(s: SenderSettings): Boolean =
        s.fcmEnabled && s.serviceAccountJson.isNotBlank() && s.notiFcmToken.isNotBlank() && s.relayKey.isNotBlank()

    enum class SendOutcome { DELIVERED, TRANSIENT, PERMANENT, NOT_CONFIGURED }

    /** Encrypted FCM push of one SMS to ippu. Logs a reason only on failure (quiet on success). */
    fun pushToIppu(context: Context, sms: CapturedSms): SendOutcome {
        val s = SenderSettings.get(context)
        if (!isConfigured(s)) return SendOutcome.NOT_CONFIGURED
        return try {
            val payload = encryptForFcm(fcmMessage(sms), s.relayKey)
            val res = fcmSender(s.serviceAccountJson).send(s.notiFcmToken, mapOf("payload" to payload))
            when {
                res.ok -> SendOutcome.DELIVERED
                res.httpCode == -1 || res.httpCode == 429 || res.httpCode in 500..599 -> {
                    Diag.log(fcmDiag(res.httpCode, res.detail)); SendOutcome.TRANSIENT
                }
                else -> { Diag.log(fcmDiag(res.httpCode, res.detail)); SendOutcome.PERMANENT }
            }
        } catch (e: Exception) {
            Diag.log("FCM → ERROR: ${e.message}"); SendOutcome.TRANSIENT
        }
    }

    /** Posts one SMS to the n8n webhook. Logs a reason only on failure. */
    fun pushToWebhook(context: Context, sms: CapturedSms): SendOutcome {
        val s = SenderSettings.get(context)
        if (!s.n8nEnabled || s.n8nUrl.isBlank()) return SendOutcome.NOT_CONFIGURED
        return try {
            val item = smsToUploadItem(sms, s.deviceId)
            val code = WebhookPoster.post(s.n8nUrl, s.n8nAuthHeaderName, s.n8nAuthValue(), UploadBatch(listOf(item)))
            when {
                code in 200..299 -> SendOutcome.DELIVERED
                code == -1 || code == 429 || code in 500..599 -> {
                    Diag.log("webhook → HTTP $code (will retry)"); SendOutcome.TRANSIENT
                }
                else -> { Diag.log("webhook → HTTP $code (check URL/auth)"); SendOutcome.PERMANENT }
            }
        } catch (e: Exception) {
            Diag.log("webhook → ERROR: ${e.message}"); SendOutcome.TRANSIENT
        }
    }

    /** The structured block placed in the n8n item's `text` field. */
    fun n8nText(sms: CapturedSms): String =
        "From: ${sms.from}\n" +
            "Message: ${sms.body}\n" +
            "Sent: ${sms.sentMillis}\n" +
            "Received: ${sms.receivedMillis}\n" +
            "Sim: ${sms.sim}"

    /** Maps a captured SMS into noti's webhook item shape (n8n leg). */
    fun smsToUploadItem(sms: CapturedSms, deviceId: String): UploadItem =
        UploadItem(
            deviceId = deviceId,
            uid = "$deviceId|sms|${sms.receivedMillis}",
            pkg = "sms",
            appLabel = "SMS",
            postTime = epochMillisToIso(sms.receivedMillis),
            title = sms.from,
            text = n8nText(sms),
            bigText = null,
            subText = null,
            category = "sms",
        )

    private fun fcmDiag(code: Int, detail: String): String = when {
        code == 401 || code == 403 -> "FCM → HTTP $code: service-account key rejected (regenerate/import the key; enable Cloud Messaging API)"
        code == 404 -> "FCM → HTTP 404: ippu token is stale/UNREGISTERED — re-pair (ippu reinstalled or data cleared)"
        code == 400 -> "FCM → HTTP 400: bad request ($detail)"
        code == -1 -> "FCM → no network / connection failed"
        else -> "FCM → HTTP $code ($detail)"
    }
}
