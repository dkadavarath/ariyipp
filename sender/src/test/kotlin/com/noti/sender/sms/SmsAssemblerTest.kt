package com.noti.sender.sms

import com.noti.shared.RelayMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsAssemblerTest {

    @Test
    fun `single part becomes from-title and body`() {
        val r = SmsAssembler.assemble(listOf(SmsPart("+123456", "Your OTP is 4567")))
        assertEquals(RelayMessage("+123456", "Your OTP is 4567"), r)
    }

    @Test
    fun `multipart bodies are concatenated in order`() {
        val r = SmsAssembler.assemble(
            listOf(SmsPart("Bank", "Hello "), SmsPart("Bank", "world"), SmsPart("Bank", "!"))
        )
        assertEquals("Bank", r?.title)
        assertEquals("Hello world!", r?.body)
    }

    @Test
    fun `empty list returns null`() {
        assertNull(SmsAssembler.assemble(emptyList()))
    }

    @Test
    fun `blank body returns null`() {
        assertNull(SmsAssembler.assemble(listOf(SmsPart("X", ""), SmsPart("X", "   "))))
    }

    @Test
    fun `missing sender falls back to Unknown`() {
        assertEquals("Unknown", SmsAssembler.assemble(listOf(SmsPart(null, "body")))?.title)
    }

    @Test
    fun `sender is taken from the first non-blank part`() {
        val r = SmsAssembler.assemble(
            listOf(SmsPart(null, "a"), SmsPart("  ", "b"), SmsPart("Real", "c"))
        )
        assertEquals("Real", r?.title)
        assertEquals("abc", r?.body)
    }
}
