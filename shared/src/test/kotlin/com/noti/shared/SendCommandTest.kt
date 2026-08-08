package com.noti.shared

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SendCommandTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `sim defaults to minus one when absent`() {
        // A command from an older noti build that predates the SIM selector.
        val cmd = json.decodeFromString<SendCommand>("""{"to":"+15551234567","body":"hi"}""")
        assertEquals(-1, cmd.sim)
    }

    @Test
    fun `sim round-trips`() {
        val cmd = SendCommand(to = "+15551234567", body = "hi", sim = 1)
        val decoded = json.decodeFromString<SendCommand>(json.encodeToString(cmd))
        assertEquals(cmd, decoded)
        assertEquals(1, decoded.sim)
    }
}
