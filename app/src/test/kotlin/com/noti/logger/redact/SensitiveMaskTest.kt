package com.noti.logger.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveMaskTest {
    private fun m(s: String) = SensitiveMask.apply(s)

    @Test fun `a one-time code after its keyword is masked`() {
        assertEquals("Your OTP is •••• - do not share", m("Your OTP is 482913 - do not share"))
        assertEquals("Verification code: ••••", m("Verification code: 4829"))
    }

    @Test fun `a full card number keeps only its last four`() {
        assertEquals("Paid with •••• 1111", m("Paid with 4111 1111 1111 1111"))
        assertEquals("card •••• 0004", m("card 5500-0000-0000-0004"))
    }

    @Test fun `what reminder rules read is left alone`() {
        val bill = "Statement for card ending 1234: total Rs 18,402.75 due by 05-03-2027"
        assertEquals(bill, m(bill))
        assertEquals("Ref 1234567890123 paid", m("Ref 1234567890123 paid")) // fails Luhn
        assertEquals("Balance 482913", m("Balance 482913")) // no OTP word
    }

    @Test fun `a code at the end of a sentence is still masked`() =
        assertEquals("Your OTP is ••••.", m("Your OTP is 482913."))

    @Test fun `amounts near code-ish words are not codes`() {
        assertEquals("Use code SAVE10 to pay Rs 2450", m("Use code SAVE10 to pay Rs 2450"))
        assertEquals("OTP sent. Amount 2450 due", m("OTP sent. Amount 2450 due"))
        assertEquals("Enter PIN to pay 2450", m("Enter PIN to pay 2450"))
        assertEquals("OTP for Rs 2450.00 is ••••", m("OTP for Rs 2450.00 is 918273"))
        assertEquals("Hotpot 1234", m("Hotpot 1234"))
        assertEquals("OTP sent. Amount is 2450", m("OTP sent. Amount is 2450"))
        // Other numbers further on in an OTP message are not the code.
        assertEquals("OTP •••• for your card. Available balance is 12500", m("OTP 482913 for your card. Available balance is 12500"))
        assertEquals("Your OTP is ••••. Ref: 20261215", m("Your OTP is 482913. Ref: 20261215"))
    }

    @Test fun `luhn`() {
        assertTrue(SensitiveMask.luhn("4111111111111111"))
        assertFalse(SensitiveMask.luhn("4111111111111112"))
    }

    @Test fun `rules keep the body when masking is off`() {
        val raw = RawNotification("p", "OTP 482913", "OTP 482913", null, null, null)
        val off = RedactionRules(RedactionConfig(emptySet(), emptyList(), captureBody = true, maskSensitive = false)).apply(raw)
        assertEquals("OTP 482913", (off as RedactedResult.Kept).text)
        val on = RedactionRules(RedactionConfig(emptySet(), emptyList(), captureBody = true)).apply(raw)
        assertEquals("OTP ••••", (on as RedactedResult.Kept).title)
    }
}
