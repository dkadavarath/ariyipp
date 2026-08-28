package com.noti.shared

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory

/**
 * Passphrase-based encryption for exported backups. A random salt derives an AES-256 key from the
 * passphrase (PBKDF2-HMAC-SHA256), which encrypts the payload with AES-GCM. The file is
 * self-describing, so restore needs only the file and the passphrase. A wrong passphrase fails the
 * GCM tag check ([decrypt] throws) — there is no recovery.
 *
 * Two on-disk formats, both handled by [decrypt]:
 *  - v1 (`MAGIC_V1 | salt | iv | ciphertext+tag`): the original format, a fixed [LEGACY_ITERATIONS]
 *    baked into the app rather than stored in the file.
 *  - v2 (`MAGIC_V2 | iterations(4 bytes BE) | salt | iv | ciphertext+tag`): current format. The
 *    iteration count travels with the file so it can be raised again later (as PBKDF2 guidance
 *    evolves) without ever breaking restore of an older backup - old files keep using whatever
 *    count they were written with, [encrypt] always writes [ITERATIONS].
 */
object BackupCrypto {

    private val MAGIC_V1 = "NOTIBK01".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V2 = "NOTIBK02".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val LEGACY_ITERATIONS = 120_000
    // OWASP's current floor for PBKDF2-HMAC-SHA256 (raised from the original 120k).
    private const val ITERATIONS = 600_000
    private const val KEY_BITS = 256

    fun encrypt(plaintext: ByteArray, passphrase: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        }
        val iterBytes = ByteBuffer.allocate(4).putInt(ITERATIONS).array()
        return MAGIC_V2 + iterBytes + salt + iv + cipher.doFinal(plaintext)
    }

    /** @throws Exception if the file isn't a noti backup or the passphrase is wrong (bad GCM tag). */
    fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        require(blob.size > MAGIC_V1.size) { "Not a valid backup file." }
        val magic = blob.copyOfRange(0, MAGIC_V1.size)
        return when {
            magic.contentEquals(MAGIC_V2) -> decryptV2(blob, passphrase)
            magic.contentEquals(MAGIC_V1) -> decryptV1(blob, passphrase)
            else -> throw IllegalArgumentException("Not a noti backup file.")
        }
    }

    private fun decryptV1(blob: ByteArray, passphrase: String): ByteArray {
        val header = MAGIC_V1.size + SALT_LEN + IV_LEN
        require(blob.size > header) { "Not a valid backup file." }
        val salt = blob.copyOfRange(MAGIC_V1.size, MAGIC_V1.size + SALT_LEN)
        val iv = blob.copyOfRange(MAGIC_V1.size + SALT_LEN, header)
        val ciphertext = blob.copyOfRange(header, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, LEGACY_ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun decryptV2(blob: ByteArray, passphrase: String): ByteArray {
        val iterOffset = MAGIC_V2.size
        val saltOffset = iterOffset + 4
        val ivOffset = saltOffset + SALT_LEN
        val header = ivOffset + IV_LEN
        require(blob.size > header) { "Not a valid backup file." }
        val iterations = ByteBuffer.wrap(blob, iterOffset, 4).int
        val salt = blob.copyOfRange(saltOffset, ivOffset)
        val iv = blob.copyOfRange(ivOffset, header)
        val ciphertext = blob.copyOfRange(header, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bits, "AES")
    }
}
