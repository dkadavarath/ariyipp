package com.noti.sender

import android.content.Context
import android.util.Log
import com.noti.sender.config.SenderSettings
import com.noti.shared.Diag
import com.noti.shared.FcmSender
import com.noti.sender.net.WebhookPoster
import com.noti.sender.sms.CapturedSms
import com.noti.shared.MessageCrypto
import com.noti.shared.RelayMessage
import com.noti.shared.UploadBatch
import com.noti.shared.UploadItem
import com.noti.shared.epochMillisToIso
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Fans a captured SMS out to two destinations, each with its own shape:
 *  - noti, over encrypted FCM: a RelayMessage (sender → title, body → text) that noti shows.
 *  - the n8n webhook, plaintext: noti's schema, with the `text` field carrying a structured
 *    From/Message/Sent/Received block.
 *
 * The pure builders are split out for unit tests; [relay] does the blocking I/O. Logs only metadata
 * (sender, length, HTTP codes) — never the body, which may be an OTP.
 */
object SenderPipeline {

    private const val TAG = "noti-sender"
    private val json = Json { encodeDefaults = true }

    // Reuse one FcmSender across relays so its cached OAuth token (valid ~1h) is kept, instead of
    // re-parsing the key, re-signing a JWT, and re-doing the token exchange on every SMS. Rebuilt only
    // when the service-account key changes. Guarded because relays can overlap across worker threads.
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

    /** The encrypted FCM data payload for noti: AES-GCM over the serialized RelayMessage. */
    fun encryptForFcm(message: RelayMessage, keyBase64: String): String =
        MessageCrypto.encrypt(json.encodeToString(message), keyBase64)

    /** What noti shows: sender "on <sim>" as the title (e.g. "+971500000000 on e&"), body verbatim. */
    fun fcmMessage(sms: CapturedSms): RelayMessage =
        RelayMessage(
            title = if (sms.sim.isNotBlank()) "${sms.from} on ${sms.sim}" else sms.from,
            body = sms.body,
            dedupe = dedupeKey(sms),
        )

    /** Stable content key so live-relay and missed-sync deliveries of the same SMS collapse on noti. */
    fun dedupeKey(sms: CapturedSms): String {
        val raw = "${sms.from}|${sms.body}|${sms.sentMillis}"
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }

    /** True when ariy has everything needed to push a relay to noti (used to gate the sync). */
    fun isConfigured(s: SenderSettings): Boolean =
        s.fcmEnabled && s.serviceAccountJson.isNotBlank() && s.notiFcmToken.isNotBlank() && s.relayKey.isNotBlank()

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

    /**
     * Runs both legs (blocking network — call off the main thread). Returns false only when the FCM
     * leg failed in a transient way worth retrying (network, 5xx, 429); a bad token/auth (other 4xx)
     * is logged but not retried. The n8n leg is best-effort.
     */
    fun relay(context: Context, sms: CapturedSms): Boolean {
        val s = SenderSettings.get(context)
        Log.i(TAG, "relaying SMS from '${sms.from}' (${sms.body.length} chars)")
        Diag.log("relay from ${sms.from} (${sms.body.length} chars)${if (sms.sim.isNotBlank()) " on ${sms.sim}" else ""}")
        var retryable = false

        if (!s.fcmEnabled) {
            Diag.log("FCM: OFF (\"Relay to ippu via FCM\" is disabled in Pairing)")
        } else if (s.serviceAccountJson.isBlank() || s.notiFcmToken.isBlank() || s.relayKey.isBlank()) {
            val missing = buildList {
                if (s.serviceAccountJson.isBlank()) add("service-account key")
                if (s.notiFcmToken.isBlank()) add("ippu token")
                if (s.relayKey.isBlank()) add("shared key")
            }.joinToString(", ")
            Diag.log("FCM: NOT PAIRED — missing $missing (Settings → Pairing)")
        } else {
            try {
                val payload = encryptForFcm(fcmMessage(sms), s.relayKey)
                val res = fcmSender(s.serviceAccountJson).send(s.notiFcmToken, mapOf("payload" to payload))
                Log.i(TAG, "FCM leg: HTTP ${res.httpCode} ok=${res.ok}")
                Diag.log(fcmDiag(res.httpCode, res.ok, res.detail))
                if (!res.ok) retryable = res.httpCode == -1 || res.httpCode == 429 || res.httpCode in 500..599
            } catch (e: Exception) {
                Log.w(TAG, "FCM leg failed: ${e.message}")
                Diag.log("FCM → ERROR: ${e.message}")
                retryable = true
            }
        }

        if (s.n8nEnabled && s.n8nUrl.isNotBlank()) {
            try {
                val item = smsToUploadItem(sms, s.deviceId)
                val code = WebhookPoster.post(s.n8nUrl, s.n8nAuthHeaderName, s.n8nAuthValue(), UploadBatch(listOf(item)))
                Log.i(TAG, "n8n leg: HTTP $code")
                Diag.log(if (code in 200..299) "webhook → HTTP $code ✓" else "webhook → HTTP $code (check URL/auth)")
            } catch (e: Exception) {
                Log.w(TAG, "n8n leg failed: ${e.message}")
                Diag.log("webhook → ERROR: ${e.message}")
            }
        }

        return !retryable
    }

    private fun fcmDiag(code: Int, ok: Boolean, detail: String): String = when {
        ok -> "FCM → HTTP 200 ✓ delivered to ippu"
        code == 401 || code == 403 -> "FCM → HTTP $code: service-account key rejected (regenerate/import the key; enable Cloud Messaging API)"
        code == 404 -> "FCM → HTTP 404: ippu token is stale/UNREGISTERED — re-pair (ippu reinstalled or data cleared)"
        code == 400 -> "FCM → HTTP 400: bad request ($detail)"
        code == -1 -> "FCM → no network / connection failed"
        else -> "FCM → HTTP $code ($detail)"
    }
}
