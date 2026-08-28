package com.noti.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory

class BackupCryptoTest {

    /** Hand-builds a v1-format blob (`MAGIC_V1 | salt | iv | ciphertext+tag`, fixed 120k
     *  iterations) exactly as the pre-v2 app would have written it, independent of whatever
     *  [BackupCrypto.encrypt] currently produces - so this test still exercises the legacy reader
     *  even after [BackupCrypto.encrypt] moves on to a v3 someday. */
    private fun legacyV1Blob(plaintext: ByteArray, passphrase: String): ByteArray {
        val magic = "NOTIBK01".toByteArray(Charsets.US_ASCII)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        }
        return magic + salt + iv + cipher.doFinal(plaintext)
    }

    @Test
    fun legacyV1Backup_stillDecrypts() {
        val plain = "an old backup made before the iteration bump".toByteArray()
        val blob = legacyV1Blob(plain, "old-passphrase")
        assertArrayEquals(plain, BackupCrypto.decrypt(blob, "old-passphrase"))
    }

    @Test
    fun newBackups_useV2Format() {
        val blob = BackupCrypto.encrypt("x".toByteArray(), "pass")
        assertTrue(String(blob.copyOfRange(0, 8), Charsets.US_ASCII) == "NOTIBK02")
    }

    @Test
    fun roundTrip_recoversPlaintext() {
        val plain = """{"hello":"world","n":42}""".toByteArray()
        val blob = BackupCrypto.encrypt(plain, "correct horse battery staple")
        val out = BackupCrypto.decrypt(blob, "correct horse battery staple")
        assertArrayEquals(plain, out)
    }

    @Test
    fun wrongPassphrase_throws() {
        val blob = BackupCrypto.encrypt("secret".toByteArray(), "right-pass")
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(blob, "wrong-pass") }
    }

    @Test
    fun notABackupFile_throws() {
        assertThrows(Exception::class.java) { BackupCrypto.decrypt("random bytes here".toByteArray(), "x") }
    }

    @Test
    fun ciphertext_isNotPlaintext() {
        val plain = "relayKeyABC123".toByteArray()
        val blob = BackupCrypto.encrypt(plain, "pass")
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("relayKeyABC123"))
    }

    @Test
    fun eachEncryption_usesFreshSaltIv() {
        val a = BackupCrypto.encrypt("same".toByteArray(), "pass")
        val b = BackupCrypto.encrypt("same".toByteArray(), "pass")
        assertFalse(a.contentEquals(b)) // random salt+iv ⇒ different ciphertext
    }
}
