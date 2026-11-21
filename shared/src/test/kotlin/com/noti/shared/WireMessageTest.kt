package com.noti.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            WireMessage.StatusRequest(forSeconds = 60),
            WireMessage.StatusRequest(forSeconds = 0),
            WireMessage.StatusConfig(alertsEnabled = false, batteryPct = 15, wifiLossMinutes = 30, pollSeconds = 20, configVersion = 9L),
            WireMessage.StatusReport(
                atMs = 1786172227000L, trigger = WireMessage.StatusReport.BATTERY_LOW, batteryPct = 14,
                charging = false, powerSave = true, signalLevel = 2, signalDbm = -103, networkType = "LTE",
                wifi = true, wifiLevel = 3, online = true, vpn = true,
            ),
            WireMessage.StatusReport(), // an all-unknown snapshot still round-trips
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

    @Test fun `an all-default status report encodes to little more than its type tag`() {
        // encodeDefaults = false is what keeps the 10s polling cadence cheap on the wire.
        assertEquals("""{"t":"status"}""", Wire.encode(WireMessage.StatusReport()))
    }

    @Test fun `a status report without the newer fields still decodes (older companion)`() {
        val old = """{"t":"status","atMs":1786172227000,"batteryPct":42}"""
        val decoded = Wire.decode(old) as WireMessage.StatusReport
        assertEquals(42, decoded.batteryPct)
        assertEquals(WireMessage.StatusReport.POLL, decoded.trigger)
        assertEquals(-1, decoded.signalLevel) // unknown, not a false zero
        assertEquals(-1, decoded.wifiLevel)
    }

    @Test fun `only a poll trigger is not an alert`() {
        assertFalse(WireMessage.StatusReport.isAlert(WireMessage.StatusReport.POLL))
        for (t in listOf(
            WireMessage.StatusReport.BATTERY_LOW,
            WireMessage.StatusReport.BATTERY_OK,
            WireMessage.StatusReport.WIFI_LOST,
            WireMessage.StatusReport.WIFI_BACK,
        )) assertTrue(WireMessage.StatusReport.isAlert(t))
    }

    @Test fun `malformed input throws`() {
        assertThrows(Exception::class.java) { Wire.decode("not json") }
        assertThrows(Exception::class.java) { Wire.decode("""{"t":"nope"}""") }
    }
}
