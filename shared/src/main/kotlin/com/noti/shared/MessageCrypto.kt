package com.noti.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
 * Long plaintexts (SMS bodies) are gzip-compressed before encryption — base64 inflates ciphertext
 * by ~33%, and FCM caps data messages at 4096 bytes, so compression buys real headroom. Decrypt
 * detects compressed content by the 2-byte gzip magic, which no plaintext we emit can start with;
 * payloads from peers running older builds (uncompressed) decrypt unchanged.
 *
 * Pure JVM (java.util.Base64 + javax.crypto, both available on minSdk 26), so it is unit-testable
 * off-device and shared unchanged by the sender app and noti.
 */
object MessageCrypto {

    private const val KEY_BYTES = 32       // AES-256
    private const val NONCE_BYTES = 12     // 96-bit GCM nonce (the recommended size)
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Only attempt compression at or above this many bytes - smaller payloads don't benefit. */
    private const val GZIP_MIN_BYTES = 64

    /** Refuses to inflate more than this many bytes - guards against a maliciously crafted small
     *  ciphertext that decompresses enormously (a "gzip bomb"), which would otherwise exhaust memory
     *  before the plaintext is even used. Generous relative to any real relay/command/config payload
     *  (a few KB at most), so no legitimate message is ever affected. */
    private const val MAX_DECOMPRESSED_BYTES = 1 shl 20 // 1 MiB

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /** A fresh random 256-bit key, base64-encoded. Load the same value into both apps at pairing. */
    fun generateKeyBase64(): String =
        encoder.encodeToString(ByteArray(KEY_BYTES).also { random.nextBytes(it) })

    /** Encrypts [plaintext]; returns base64(nonce || ciphertext+tag). Compresses when smaller. */
    fun encrypt(plaintext: String, keyBase64: String): String =
        encryptBytes(plaintext.toByteArray(Charsets.UTF_8), keyBase64)

    /** Byte-array variant of [encrypt]; compression kicks in above [GZIP_MIN_BYTES]. */
    fun encryptBytes(plaintext: ByteArray, keyBase64: String): String {
        val body = if (plaintext.size >= GZIP_MIN_BYTES) {
            gzip(plaintext)?.takeIf { it.size < plaintext.size } ?: plaintext
        } else {
            plaintext
        }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey(keyBase64), GCMParameterSpec(TAG_BITS, nonce))
        }
        val ciphertext = cipher.doFinal(body)
        return encoder.encodeToString(nonce + ciphertext)
    }

    /**
     * Decrypts a payload from [encrypt]. Throws [GeneralSecurityException] if the key is wrong or the
     * data was tampered with (GCM authentication failure), and [IllegalArgumentException] if the
     * payload is malformed. Callers on the receive path must catch these and drop the message.
     */
    fun decrypt(payloadBase64: String, keyBase64: String): String =
        String(decryptToBytes(payloadBase64, keyBase64), Charsets.UTF_8)

    /** Byte-array variant of [decrypt]; transparently inflates gzipped content (see class docs). */
    fun decryptToBytes(payloadBase64: String, keyBase64: String): ByteArray {
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
        val plaintext = cipher.doFinal(ciphertext)
        // Sniff AFTER authentication: these bytes can only have come from someone holding the key,
        // so a gzip-magic prefix is trustworthy. JSON never starts with 0x1f 0x8b.
        return if (isGzip(plaintext)) gunzip(plaintext) else plaintext
    }

    private fun isGzip(b: ByteArray) =
        b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    private fun gzip(data: ByteArray): ByteArray? = try {
        val bos = ByteArrayOutputStream(data.size / 2 + 32)
        GZIPOutputStream(bos).use { it.write(data) }
        bos.toByteArray()
    } catch (e: Exception) {
        null
    }

    private class DecompressedTooLargeException : Exception()

    private fun gunzip(data: ByteArray): ByteArray = try {
        val out = ByteArrayOutputStream(minOf(data.size * 4, MAX_DECOMPRESSED_BYTES))
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_DECOMPRESSED_BYTES) throw DecompressedTooLargeException()
                out.write(buf, 0, n)
            }
        }
        out.toByteArray()
    } catch (e: DecompressedTooLargeException) {
        throw GeneralSecurityException("decompressed payload exceeds $MAX_DECOMPRESSED_BYTES bytes")
    } catch (e: Exception) {
        throw GeneralSecurityException("gzipped payload is corrupt", e)
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
