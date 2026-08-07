package com.noti.shared

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for the small payloads relayed between the sender app and noti, using a
 * pre-shared 256-bit key (AES-256-GCM). AES-256 is already quantum-resistant for this threat model,
 * so no post-quantum machinery is needed.
 *
 * Wire format (then base64-encoded): nonce(12 bytes) || ciphertext-with-tag. A fresh random 96-bit
 * nonce is generated per message and never reused with a given key — the one invariant GCM demands.
 *
 * Pure JVM (java.util.Base64 + javax.crypto, both available on minSdk 26), so it is unit-testable
 * off-device and shared unchanged by the sender app and noti.
 */
object MessageCrypto {

    private const val KEY_BYTES = 32       // AES-256
    private const val NONCE_BYTES = 12     // 96-bit GCM nonce (the recommended size)
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /** A fresh random 256-bit key, base64-encoded. Load the same value into both apps at pairing. */
    fun generateKeyBase64(): String =
        encoder.encodeToString(ByteArray(KEY_BYTES).also { random.nextBytes(it) })

    /** Encrypts [plaintext]; returns base64(nonce || ciphertext+tag). */
    fun encrypt(plaintext: String, keyBase64: String): String {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey(keyBase64), GCMParameterSpec(TAG_BITS, nonce))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return encoder.encodeToString(nonce + ciphertext)
    }

    /**
     * Decrypts a payload from [encrypt]. Throws [GeneralSecurityException] if the key is wrong or the
     * data was tampered with (GCM authentication failure), and [IllegalArgumentException] if the
     * payload is malformed. Callers on the receive path must catch these and drop the message.
     */
    fun decrypt(payloadBase64: String, keyBase64: String): String {
        val blob = try {
            decoder.decode(payloadBase64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("payload is not valid base64", e)
        }
        require(blob.size > NONCE_BYTES) { "payload too short" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val ciphertext = blob.copyOfRange(NONCE_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(keyBase64), GCMParameterSpec(TAG_BITS, nonce))
        }
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(keyBase64: String): SecretKeySpec {
        val bytes = try {
            decoder.decode(keyBase64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("key is not valid base64", e)
        }
        require(bytes.size == KEY_BYTES) {
            "key must be $KEY_BYTES bytes (256-bit) once base64-decoded, got ${bytes.size}"
        }
        return SecretKeySpec(bytes, "AES")
    }
}
