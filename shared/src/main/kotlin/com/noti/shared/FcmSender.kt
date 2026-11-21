package com.noti.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Result of an FCM v1 send: the HTTP status, whether it succeeded, and a short detail. */
data class FcmSendResult(val httpCode: Int, val ok: Boolean, val detail: String)

/**
 * Sends FCM v1 messages directly from the device, authenticating with a user-supplied service-account
 * key (the legacy static server key was retired by Google in 2024). It mints a short-lived OAuth2
 * token by signing a JWT (RS256) with the account's private key and caches it until it nears expiry.
 *
 * Pure JVM (java.security + HttpURLConnection + kotlinx.serialization), so the on-device code path is
 * unit-testable off-device. Network calls block — call from a background thread.
 */
class FcmSender(serviceAccountJson: String) {

    companion object {
        /** FCM's own constraint on an analytics label; anything else is rejected with HTTP 400. */
        private val LABEL_RE = Regex("^[a-zA-Z0-9-_.~%]{1,50}$")

        /** The v1 `messages:send` body. Pure, so the wire shape is unit-testable. An invalid
         *  [label] is dropped rather than sent - a bad tag must never cost a delivery. */
        fun requestBody(
            targetToken: String,
            data: Map<String, String>,
            validateOnly: Boolean,
            label: String?,
            highPriority: Boolean = true,
            collapseKey: String? = null,
            ttlSeconds: Int? = null,
        ): String =
            buildJsonObject {
                put("validate_only", validateOnly)
                putJsonObject("message") {
                    put("token", targetToken)
                    // High priority so the data message wakes the receiver immediately, even under Doze /
                    // App Standby / a locked screen — otherwise normal-priority data is deferred until the
                    // device next becomes interactive (i.e. only shows on unlock).
                    // [highPriority] = false is only for background housekeeping that may wait for the
                    // receiver's next maintenance window (the routine heartbeat): FCM deprioritises
                    // apps whose high-priority pushes never show anything, which would slow the
                    // pushes that do matter (relays, commands, force-checks).
                    putJsonObject("android") {
                        put("priority", if (highPriority) "high" else "normal")
                        // FCM keeps at most 100 undelivered non-collapsible messages per device and
                        // drops them all past that. Housekeeping (heartbeats) shares one collapse
                        // slot and expires, so a long outage can't fill the queue and cost relays.
                        if (collapseKey != null) put("collapse_key", collapseKey)
                        if (ttlSeconds != null) put("ttl", "${ttlSeconds}s")
                    }
                    putJsonObject("data") { data.forEach { (k, v) -> put(k, v) } }
                    if (label != null && LABEL_RE.matches(label)) {
                        putJsonObject("fcm_options") { put("analytics_label", label) }
                    }
                }
            }.toString()

        // One instance per service-account key, process-wide: keeps the parsed key, the derived RSA
        // private key, and the cached OAuth token (~1h) alive across all callers (heartbeat, command
        // sends, SMS relay). Without this, every send re-parses the JSON, re-signs a JWT, and pays an
        // extra HTTPS round-trip to the token endpoint. Guarded because sends can overlap across
        // worker threads.
        @Volatile private var cached: FcmSender? = null
        @Volatile private var cachedKey: String = ""

        @Synchronized
        fun forServiceAccount(serviceAccountJson: String): FcmSender {
            cached?.let { if (cachedKey == serviceAccountJson) return it }
            return FcmSender(serviceAccountJson).also {
                cached = it
                cachedKey = serviceAccountJson
            }
        }
    }

    @Serializable
    private data class ServiceAccount(
        @SerialName("client_email") val clientEmail: String,
        @SerialName("private_key") val privateKey: String,
        @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
        @SerialName("project_id") val projectId: String,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val account = json.decodeFromString<ServiceAccount>(serviceAccountJson)

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryEpoch: Long = 0

    /** The project this key sends for (must match the receiver's Firebase project). */
    val projectId: String get() = account.projectId

    /**
     * Sends [data] to the device identified by [targetToken]. With [validateOnly] the message is
     * checked by FCM but not delivered — useful for verifying credentials without a live device.
     * [label] (see [FcmLabel]) tags the message in Firebase's delivery reports and BigQuery export,
     * so "accepted but never delivered" can be told apart per message type and direction.
     */
    fun send(
        targetToken: String,
        data: Map<String, String>,
        validateOnly: Boolean = false,
        label: String? = null,
        highPriority: Boolean = true,
        collapseKey: String? = null,
        ttlSeconds: Int? = null,
    ): FcmSendResult {
        val token = accessToken()
        val body = requestBody(targetToken, data, validateOnly, label, highPriority, collapseKey, ttlSeconds)

        val (code, resp) = httpPost(
            url = "https://fcm.googleapis.com/v1/projects/${account.projectId}/messages:send",
            contentType = "application/json",
            body = body.toByteArray(Charsets.UTF_8),
            bearer = token,
        )
        // A 401 means the cached OAuth token is no longer accepted (revoked, or the device clock
        // jumped and the expiry check was fooled). Drop it so the next send fetches a fresh one
        // instead of failing for up to an hour.
        if (code == 401) invalidateToken()
        return FcmSendResult(code, code in 200..299, summarize(resp))
    }

    // ---- OAuth2 (JWT bearer) ----

    @Synchronized
    private fun invalidateToken() {
        cachedToken = null
        tokenExpiryEpoch = 0
    }

    @Synchronized
    private fun accessToken(): String {
        val now = System.currentTimeMillis() / 1000
        cachedToken?.let { if (now < tokenExpiryEpoch - 60) return it }

        val assertion = signedAssertion(now)
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
            "&assertion=" + URLEncoder.encode(assertion, "UTF-8")
        val (code, resp) = httpPost(account.tokenUri, "application/x-www-form-urlencoded", form.toByteArray(), null)
        if (code !in 200..299) throw IOException("OAuth token exchange failed: HTTP $code $resp")

        val tr = json.decodeFromString<TokenResponse>(resp)
        cachedToken = tr.accessToken
        tokenExpiryEpoch = now + tr.expiresIn
        return tr.accessToken
    }

    private fun signedAssertion(nowEpoch: Long): String {
        val header = urlEncoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = urlEncoder.encodeToString(
            buildJsonObject {
                put("iss", account.clientEmail)
                put("scope", "https://www.googleapis.com/auth/firebase.messaging")
                put("aud", account.tokenUri)
                put("iat", nowEpoch)
                put("exp", nowEpoch + 3600)
            }.toString().toByteArray()
        )
        val signingInput = "$header.$claims"
        val signature = urlEncoder.encodeToString(sign(signingInput.toByteArray(Charsets.US_ASCII)))
        return "$signingInput.$signature"
    }

    private fun sign(data: ByteArray): ByteArray {
        val der = Base64.getDecoder().decode(
            account.privateKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
        )
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        return Signature.getInstance("SHA256withRSA").run {
            initSign(key); update(data); sign()
        }
    }

    // ---- HTTP ----

    private fun httpPost(url: String, contentType: String, body: ByteArray, bearer: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        // No disconnect() here: with the streams closed (below), the connection returns to the JVM's
        // keep-alive pool, so the next send skips DNS+TCP+TLS. disconnect() would kill that socket.
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to resp
        } catch (e: IOException) {
            // A timeout or reset mid-exchange leaves the socket unusable; release it rather than
            // leaving it for the GC (the success path above keeps it pooled).
            conn.disconnect()
            throw e
        }
    }

    /** Pulls the message id (success) or the error status (failure) out of an FCM response body. */
    private fun summarize(resp: String): String {
        if (resp.isBlank()) return ""
        return try {
            val o = json.parseToJsonElement(resp).jsonObject
            o["name"]?.jsonPrimitive?.content
                ?: o["error"]?.jsonObject?.get("status")?.jsonPrimitive?.content
                ?: resp.take(200)
        } catch (e: Exception) {
            resp.take(200)
        }
    }
}
