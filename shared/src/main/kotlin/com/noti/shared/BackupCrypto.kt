package com.noti.shared

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
 * self-describing: `MAGIC | salt | iv | ciphertext+tag`, so restore needs only the file and the
 * passphrase. A wrong passphrase fails the GCM tag check ([decrypt] throws) — there is no recovery.
 */
object BackupCrypto {

    private val MAGIC = "NOTIBK01".toByteArray(Charsets.US_ASCII) // 8 bytes, also a format version
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun encrypt(plaintext: ByteArray, passphrase: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        return MAGIC + salt + iv + cipher.doFinal(plaintext)
    }

    /** @throws Exception if the file isn't a noti backup or the passphrase is wrong (bad GCM tag). */
    fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        val header = MAGIC.size + SALT_LEN + IV_LEN
        require(blob.size > header) { "Not a valid backup file." }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a noti backup file." }
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = blob.copyOfRange(MAGIC.size + SALT_LEN, header)
        val ciphertext = blob.copyOfRange(header, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bits, "AES")
    }
}
