package com.noti.sender.fcm

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
     */
    fun send(targetToken: String, data: Map<String, String>, validateOnly: Boolean = false): FcmSendResult {
        val token = accessToken()
        val body = buildJsonObject {
            put("validate_only", validateOnly)
            putJsonObject("message") {
                put("token", targetToken)
                // High priority so the data message wakes the receiver immediately, even under Doze /
                // App Standby / a locked screen — otherwise normal-priority data is deferred until the
                // device next becomes interactive (i.e. only shows on unlock).
                putJsonObject("android") { put("priority", "high") }
                putJsonObject("data") { data.forEach { (k, v) -> put(k, v) } }
            }
        }.toString()

        val (code, resp) = httpPost(
            url = "https://fcm.googleapis.com/v1/projects/${account.projectId}/messages:send",
            contentType = "application/json",
            body = body.toByteArray(Charsets.UTF_8),
            bearer = token,
        )
        return FcmSendResult(code, code in 200..299, summarize(resp))
    }

    // ---- OAuth2 (JWT bearer) ----

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
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to resp
        } finally {
            conn.disconnect()
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
