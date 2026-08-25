package com.noti.sender

import android.content.Context
import com.noti.sender.config.SenderSettings
import com.noti.shared.Diag
import com.noti.shared.FcmSendResult
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
 * leg. Logs only metadata (sender, length, HTTP codes) - never the body, which may be an OTP.
 */
object SenderPipeline {

    // The per-key instance cache lives in FcmSender's companion, shared process-wide by every
    // caller (this pipeline, heartbeat, command sends) so the OAuth token survives across all of them.
    private fun fcmSender(serviceAccountJson: String): FcmSender =
        FcmSender.forServiceAccount(serviceAccountJson)

    /** The encrypted FCM data payload for ippu: AES-GCM over the serialized wire message. */
    fun encryptForFcm(message: WireMessage, keyBase64: String): String =
        MessageCrypto.encrypt(Wire.encode(message), keyBase64)

    /**
     * FCM v1 caps a data message at 4096 bytes, and our envelope (token ~150-250 chars + JSON
     * scaffolding + base64's +33%) eats several hundred of those. A payload over this pre-encryption
     * character budget risks a permanent HTTP 400 that would abort whole-inbox repushes — so long
     * bodies are split into part-relays instead (reassembled by ippu).
     */
    const val MAX_PAYLOAD_CHARS = 2_500

    private const val MAX_PARTS = 8

    /**
     * Encrypted FCM payloads for [message]: one element for a normal relay, or one per body chunk
     * when the whole payload would exceed [MAX_PAYLOAD_CHARS]. Pure - unit-testable.
     */
    fun encryptedPayloads(message: WireMessage.Relay, keyBase64: String): List<String> {
        val whole = encryptForFcm(message, keyBase64)
        if (whole.length <= MAX_PAYLOAD_CHARS || message.body.isEmpty()) return listOf(whole)

        // Find the smallest part count whose every chunk fits the budget.
        var parts = 2
        var chunks: List<String> = emptyList()
        while (parts <= MAX_PARTS) {
            chunks = splitBody(message.body, parts)
            val allFit = chunks.all { chunk ->
                encryptForFcm(message.copy(body = chunk, parts = parts), keyBase64).length <= MAX_PAYLOAD_CHARS
            }
            if (allFit) break
            parts++
        }
        if (parts > MAX_PARTS) return listOf(whole) // unsplittable; let FCM reject as before

        return chunks.mapIndexed { i, chunk ->
            encryptForFcm(message.copy(body = chunk, part = i, parts = parts), keyBase64)
        }
    }

    /** Splits [body] into exactly [parts] slices at code-point boundaries (never inside an emoji). */
    private fun splitBody(body: String, parts: Int): List<String> {
        val out = ArrayList<String>(parts)
        val approxPerPart = body.length / parts
        var start = 0
        for (i in 0 until parts) {
            var end = if (i == parts - 1) body.length else minOf(body.length, start + approxPerPart)
            // Keep surrogate pairs together.
            if (end in (start + 1) until body.length &&
                Character.isHighSurrogate(body[end - 1]) && Character.isLowSurrogate(body[end])
            ) end--
            out.add(body.substring(start, maxOf(start, end)))
            start = maxOf(start, end)
        }
        return out
    }

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

    /** Encrypted FCM push of one SMS to ippu. Logs a reason only on failure (quiet on success).
     *  Bodies too large for one FCM data message are sent as part-relays (see [encryptedPayloads]);
     *  the send stops at the first failed leg so retries re-send from that part. */
    fun pushToIppu(context: Context, sms: CapturedSms): SendOutcome {
        val s = SenderSettings.get(context)
        if (!isConfigured(s)) return SendOutcome.NOT_CONFIGURED
        return try {
            val payloads = encryptedPayloads(fcmMessage(sms), s.relayKey)
            var res: FcmSendResult? = null
            for (payload in payloads) {
                res = fcmSender(s.serviceAccountJson).send(s.notiFcmToken, mapOf("payload" to payload))
                if (!res.ok) break
            }
            val r = res!!
            when {
                r.ok -> SendOutcome.DELIVERED
                r.httpCode == -1 || r.httpCode == 429 || r.httpCode in 500..599 -> {
                    Diag.log(fcmDiag(r.httpCode, r.detail)); SendOutcome.TRANSIENT
                }
                else -> { Diag.log(fcmDiag(r.httpCode, r.detail)); SendOutcome.PERMANENT }
            }
        } catch (e: Exception) {
            Diag.log("FCM → ERROR: ${e.message}"); SendOutcome.TRANSIENT
        }
    }

    /**
     * Companion → Main: announce this device's push endpoint (its FCM token) so Main can reach it
     * for reverse-send, with nothing copied back by hand. Sent after pairing and on token refresh.
     */
    fun announceToken(context: Context): SendOutcome {
        val s = SenderSettings.get(context)
        if (s.serviceAccountJson.isBlank() || s.notiFcmToken.isBlank() || s.relayKey.isBlank() || s.myFcmToken.isBlank()) {
            return SendOutcome.NOT_CONFIGURED
        }
        return try {
            val payload = MessageCrypto.encrypt(Wire.encode(WireMessage.Token(s.myFcmToken)), s.relayKey)
            val res = fcmSender(s.serviceAccountJson).send(s.notiFcmToken, mapOf("payload" to payload))
            when {
                res.ok -> { Diag.log("endpoint announced to main"); SendOutcome.DELIVERED }
                res.httpCode == -1 || res.httpCode == 429 || res.httpCode in 500..599 -> SendOutcome.TRANSIENT
                else -> { Diag.log(fcmDiag(res.httpCode, res.detail)); SendOutcome.PERMANENT }
            }
        } catch (e: Exception) {
            SendOutcome.TRANSIENT
        }
    }

    /**
     * Companion → Main: reports what happened to a [msgId] previously received in a Command, so
     * Main can update that message's ticks. Best-effort - a failure here just leaves the ticks
     * showing whatever the last successful ack said (or none), it doesn't retry or block sending.
     */
    fun pushAckToIppu(context: Context, msgId: Long, status: Int): SendOutcome {
        val s = SenderSettings.get(context)
        if (s.serviceAccountJson.isBlank() || s.notiFcmToken.isBlank() || s.relayKey.isBlank()) {
            return SendOutcome.NOT_CONFIGURED
        }
        return try {
            val payload = MessageCrypto.encrypt(Wire.encode(WireMessage.DeliveryAck(msgId, status)), s.relayKey)
            val res = fcmSender(s.serviceAccountJson).send(s.notiFcmToken, mapOf("payload" to payload))
            when {
                res.ok -> SendOutcome.DELIVERED
                res.httpCode == -1 || res.httpCode == 429 || res.httpCode in 500..599 -> SendOutcome.TRANSIENT
                else -> { Diag.log(fcmDiag(res.httpCode, res.detail)); SendOutcome.PERMANENT }
            }
        } catch (e: Exception) {
            SendOutcome.TRANSIENT
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
        code == 404 -> "FCM → HTTP 404: main token is stale/UNREGISTERED - re-pair (main reinstalled or data cleared)"
        code == 400 -> "FCM → HTTP 400: bad request ($detail)"
        code == -1 -> "FCM → no network / connection failed"
        else -> "FCM → HTTP $code ($detail)"
    }
}
