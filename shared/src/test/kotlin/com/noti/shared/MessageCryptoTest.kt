package com.noti.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64

class MessageCryptoTest {

    private val key = MessageCrypto.generateKeyBase64()

    // ---- Round trips ----

    @Test
    fun `round-trips a typical OTP message`() {
        val plain = "Your verification code is 483920"
        assertEquals(plain, MessageCrypto.decrypt(MessageCrypto.encrypt(plain, key), key))
    }

    @Test
    fun `round-trips unicode and emoji`() {
        val plain = "OTP: 12345 — café ☕ 日本語 🔐"
        assertEquals(plain, MessageCrypto.decrypt(MessageCrypto.encrypt(plain, key), key))
    }

    @Test
    fun `round-trips an empty string`() {
        assertEquals("", MessageCrypto.decrypt(MessageCrypto.encrypt("", key), key))
    }

    @Test
    fun `round-trips a long multi-segment SMS`() {
        val plain = "A".repeat(600) + " end"
        assertEquals(plain, MessageCrypto.decrypt(MessageCrypto.encrypt(plain, key), key))
    }

    @Test
    fun `a generated key round-trips`() {
        val k = MessageCrypto.generateKeyBase64()
        assertEquals("hello", MessageCrypto.decrypt(MessageCrypto.encrypt("hello", k), k))
    }

    // ---- Nonce uniqueness ----

    @Test
    fun `same plaintext and key produce different ciphertexts (fresh nonce each time)`() {
        val a = MessageCrypto.encrypt("same", key)
        val b = MessageCrypto.encrypt("same", key)
        assertNotEquals(a, b)
    }

    // ---- Authentication failures ----

    @Test
    fun `decrypting with the wrong key fails`() {
        val other = MessageCrypto.generateKeyBase64()
        val payload = MessageCrypto.encrypt("secret", key)
        assertThrows(GeneralSecurityException::class.java) { MessageCrypto.decrypt(payload, other) }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val blob = Base64.getDecoder().decode(MessageCrypto.encrypt("secret", key))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte() // flip a tag bit
        val tampered = Base64.getEncoder().encodeToString(blob)
        assertThrows(GeneralSecurityException::class.java) { MessageCrypto.decrypt(tampered, key) }
    }

    // ---- Malformed input ----

    @Test
    fun `payload shorter than the nonce is rejected`() {
        val tooShort = Base64.getEncoder().encodeToString(ByteArray(8))
        assertThrows(IllegalArgumentException::class.java) { MessageCrypto.decrypt(tooShort, key) }
    }

    @Test
    fun `non-base64 payload is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MessageCrypto.decrypt("not base64!!!", key) }
    }

    @Test
    fun `key of the wrong length is rejected`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16)) // 128-bit, not 256
        assertThrows(IllegalArgumentException::class.java) { MessageCrypto.encrypt("x", shortKey) }
    }

    @Test
    fun `non-base64 key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MessageCrypto.encrypt("x", "not a key!!!") }
    }

    // ---- Key generation ----

    @Test
    fun `generated keys are 256-bit and distinct`() {
        val k1 = MessageCrypto.generateKeyBase64()
        val k2 = MessageCrypto.generateKeyBase64()
        assertEquals(32, Base64.getDecoder().decode(k1).size)
        assertNotEquals(k1, k2)
    }

    @Test
    fun `whitespace around a key is tolerated`() {
        val payload = MessageCrypto.encrypt("hi", key)
        assertEquals("hi", MessageCrypto.decrypt(payload, "  $key\n"))
    }
}
