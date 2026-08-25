package com.noti.sender

import com.noti.sender.sms.CapturedSms
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderPipelineTest {

    @Test
    fun `encryptForFcm produces a payload noti can decrypt back to the message`() {
        val key = MessageCrypto.generateKeyBase64()
        val msg = WireMessage.Relay(title = "Bank", body = "Your OTP is 4567")

        val payload = SenderPipeline.encryptForFcm(msg, key)
        val plain = MessageCrypto.decrypt(payload, key)
        val decoded = Wire.decode(plain)

        assertEquals(msg, decoded)
    }

    @Test
    fun `fcm message shows sender on the sim name as the title`() {
        val sms = CapturedSms("+971500000000", "Test6", 1L, 2L, "e&")
        val m = SenderPipeline.fcmMessage(sms)
        assertEquals("+971500000000 on e&", m.title)
        assertEquals("Test6", m.body)
    }

    @Test
    fun `fcm title omits the on-suffix when the sim is blank`() {
        val m = SenderPipeline.fcmMessage(CapturedSms("+123", "hi", 1L, 2L, ""))
        assertEquals("+123", m.title)
    }

    @Test
    fun `n8n text uses the From-Message-Sent-Received-Sim block`() {
        val sms = CapturedSms("+971500000000", "Test6", 1786172225000L, 1786172227087L, "e&")
        assertEquals(
            "From: +971500000000\n" +
                "Message: Test6\n" +
                "Sent: 1786172225000\n" +
                "Received: 1786172227087\n" +
                "Sim: e&",
            SenderPipeline.n8nText(sms)
        )
    }

    @Test
    fun `smsToUploadItem carries the block in the text field and maps the rest`() {
        val sms = CapturedSms("+971500000000", "Test6", 1786172225000L, 1786172227087L, "sim1")
        val item = SenderPipeline.smsToUploadItem(sms, "dev-1")

        assertEquals(SenderPipeline.n8nText(sms), item.text)
        assertEquals("+971500000000", item.title)
        assertEquals("sms", item.pkg)
        assertEquals("sms", item.category)
        assertEquals("SMS", item.appLabel)
        assertEquals("dev-1", item.deviceId)
        assertTrue(item.uid.startsWith("dev-1|sms|"))
    }

    // ---- FCM payload size guard / chunking ----

    private fun relay(body: String) = WireMessage.Relay(
        title = "+971500000000 on e&",
        body = body,
        dedupe = "abc123",
        time = 1786172227000L,
    )

    @Test
    fun `a small message stays a single payload`() {
        val key = MessageCrypto.generateKeyBase64()
        val payloads = SenderPipeline.encryptedPayloads(relay("Your OTP is 4567"), key)
        assertEquals(1, payloads.size)
        assertTrue(payloads[0].length <= SenderPipeline.MAX_PAYLOAD_CHARS)
    }

    /** High-entropy text that gzip can't shrink - forces the size guard down its chunking path. */
    private fun incompressible(chars: Int, seed: Long = 42): String {
        val rng = java.util.Random(seed)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,!?-"
        return buildString(chars) { repeat(chars) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }

    @Test
    fun `an oversized body is split into parts that each fit the budget`() {
        val key = MessageCrypto.generateKeyBase64()
        val body = incompressible(6000) // ~6 KB - far past one FCM data message
        val payloads = SenderPipeline.encryptedPayloads(relay(body), key)

        assertTrue(payloads.size > 1)
        for (p in payloads) {
            assertTrue(p.length <= SenderPipeline.MAX_PAYLOAD_CHARS)

            val decoded = Wire.decode(MessageCrypto.decrypt(p, key)) as WireMessage.Relay
            assertEquals(payloads.size, decoded.parts)
            assertEquals(relay("").title, decoded.title)
            assertEquals(relay("").dedupe, decoded.dedupe)
        }
        // Parts arrive as 0..n-1 and reassemble to the exact original body.
        val reassembled = payloads
            .map { Wire.decode(MessageCrypto.decrypt(it, key)) as WireMessage.Relay }
            .sortedBy { it.part }
            .joinToString("") { it.body }
        assertEquals(body, reassembled)
    }

    @Test
    fun `splitting never breaks inside a surrogate pair`() {
        val key = MessageCrypto.generateKeyBase64()
        // Varied emoji + random filler keeps entropy high AND guarantees pairs straddle any naive
        // char-count boundary.
        val rng = java.util.Random(7)
        val emojis = arrayOf("\uD83D\uDE00", "\uD83D\uDE01", "\uD83D\uDE02", "\uD83C\uDF89", "\uD83D\uDCF1")
        val body = buildString {
            while (length < 6000) {
                append(emojis[rng.nextInt(emojis.size)])
                if (rng.nextBoolean()) append(incompressible(3, length.toLong()))
            }
        }
        val payloads = SenderPipeline.encryptedPayloads(relay(body), key)
        assertTrue(payloads.size > 1)

        val reassembled = payloads
            .map { Wire.decode(MessageCrypto.decrypt(it, key)) as WireMessage.Relay }
            .sortedBy { it.part }
            .joinToString("") { it.body }
        assertEquals(body, reassembled)
        // Every part is well-formed on its own too (no lone surrogates at a cut point).
        for (decoded in payloads.map { Wire.decode(MessageCrypto.decrypt(it, key)) as WireMessage.Relay }) {
            val b = decoded.body
            for (i in b.indices) {
                assertFalse("lone high surrogate at $i", Character.isHighSurrogate(b[i]) && (i + 1 >= b.length || !Character.isLowSurrogate(b[i + 1])))
                assertFalse("lone low surrogate at $i", Character.isLowSurrogate(b[i]) && (i == 0 || !Character.isHighSurrogate(b[i - 1])))
            }
        }
    }

    @Test
    fun `encodeDefaults=false omits unset fields but decodes back equal`() {
        val minimal = WireMessage.Relay(body = "hi")
        val json = Wire.encode(minimal)
        assertFalse(json.contains("\"title\""))
        assertFalse(json.contains("\"part\""))
        assertEquals(minimal, Wire.decode(json))
    }
}
