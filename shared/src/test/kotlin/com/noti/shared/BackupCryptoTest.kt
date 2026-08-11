package com.noti.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupCryptoTest {

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
