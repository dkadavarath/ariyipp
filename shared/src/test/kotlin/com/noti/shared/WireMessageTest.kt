package com.noti.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireMessageTest {

    @Test fun `each variant round-trips`() {
        val messages = listOf(
            WireMessage.Relay(title = "Bank on e&", body = "OTP 4567", dedupe = "abc123", time = 1786172227000L),
            WireMessage.Command(to = "+971500000000", body = "hi", sim = 1, msgId = 42L),
            WireMessage.DeliveryAck(msgId = 42L, status = WireMessage.DeliveryAck.SMS_SENT),
            WireMessage.WebhookConfig(enabled = true, url = "https://n8n.example/hook", authHeaderName = "key", authHeaderPrefix = "", authToken = "s3cr3t"),
            WireMessage.Token(endpoint = "fcm-token-xyz"),
            WireMessage.Heartbeat(request = true),
            WireMessage.Heartbeat(request = false),
        )
        for (m in messages) assertEquals(m, Wire.decode(Wire.encode(m)))
    }

    @Test fun `encoding carries a type discriminator`() {
        assertTrue(Wire.encode(WireMessage.Relay(body = "x")).contains("\"t\":\"relay\""))
        assertTrue(Wire.encode(WireMessage.Command(to = "+1")).contains("\"t\":\"command\""))
    }

    @Test fun `decode dispatches to the right type`() {
        val decoded = Wire.decode(Wire.encode(WireMessage.Command(to = "+123", body = "yo", sim = 0)))
        assertTrue(decoded is WireMessage.Command)
        assertEquals("+123", (decoded as WireMessage.Command).to)
    }

    @Test fun `unknown fields are ignored (forward compatible)`() {
        val withExtra = """{"t":"token","endpoint":"abc","futureField":42}"""
        assertEquals(WireMessage.Token("abc"), Wire.decode(withExtra))
    }

    @Test fun `malformed input throws`() {
        assertThrows(Exception::class.java) { Wire.decode("not json") }
        assertThrows(Exception::class.java) { Wire.decode("""{"t":"nope"}""") }
    }
}
