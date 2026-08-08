package com.noti.sender.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsAssemblerTest {

    @Test
    fun `single part captures sender, body, timestamps, and sim`() {
        val r = SmsAssembler.assemble(listOf(SmsPart("+123456", "Your OTP is 4567", 1000L)), 2000L, "sim1")
        assertEquals(CapturedSms("+123456", "Your OTP is 4567", 1000L, 2000L, "sim1"), r)
    }

    @Test
    fun `multipart bodies concatenated, sender and sent-time from the first part`() {
        val r = SmsAssembler.assemble(
            listOf(SmsPart("Bank", "Hello ", 500L), SmsPart("Bank", "world", 600L), SmsPart("Bank", "!", 700L)),
            9000L, "sim2"
        )
        assertEquals("Bank", r?.from)
        assertEquals("Hello world!", r?.body)
        assertEquals(500L, r?.sentMillis)
        assertEquals(9000L, r?.receivedMillis)
        assertEquals("sim2", r?.sim)
    }

    @Test
    fun `empty list returns null`() {
        assertNull(SmsAssembler.assemble(emptyList(), 1L, "sim1"))
    }

    @Test
    fun `blank body returns null`() {
        assertNull(SmsAssembler.assemble(listOf(SmsPart("X", "", 1L), SmsPart("X", "   ", 1L)), 2L, "sim1"))
    }

    @Test
    fun `missing sender falls back to Unknown`() {
        assertEquals("Unknown", SmsAssembler.assemble(listOf(SmsPart(null, "body", 1L)), 2L, "sim1")?.from)
    }
}
