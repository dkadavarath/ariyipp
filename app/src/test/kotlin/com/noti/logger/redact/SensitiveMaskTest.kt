package com.noti.logger.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveMaskTest {
    private fun m(s: String) = SensitiveMask.apply(s)

    @Test fun `the code is masked, not the card it is for`() =
        assertEquals("OTP for card ending 1234 is ••••", m("OTP for card ending 1234 is 482913"))

    @Test fun `codes before the word, split or prefixed are masked`() {
        assertEquals("•••• is your OTP for login", m("482913 is your OTP for login"))
        assertEquals("Your verification code is ••••", m("Your verification code is 123 456"))
        assertEquals("Use G-•••• to verify", m("Use G-482913 to verify"))
        assertEquals("Verification code: ••••", m("Verification code: 4829"))
        assertEquals("Your OTP is ••••.", m("Your OTP is 482913."))
    }

    @Test fun `tails, amounts, dates and times stay`() {
        assertEquals("OTP •••• for a/c XX1234, Rs 2450.00 at 10:30 on 05/03/2027", m("OTP 482913 for a/c XX1234, Rs 2450.00 at 10:30 on 05/03/2027"))
        assertEquals("OTP for Rs 2450 is ••••", m("OTP for Rs 2450 is 918273"))
    }

    @Test fun `messages without an OTP word keep their numbers`() {
        val bill = "Statement for card ending 1234: total Rs 18,402.75 due by 05-03-2027. Ref 482913"
        assertEquals(bill, m(bill))
        assertEquals("Balance 482913", m("Balance 482913"))
        assertEquals("Hotpot 1234", m("Hotpot 1234"))
    }

    @Test fun `a full card number keeps only its last four`() {
        assertEquals("Paid with •••• 1111", m("Paid with 4111 1111 1111 1111"))
        assertEquals("card •••• 0004", m("card 5500-0000-0000-0004"))
        assertEquals("OTP •••• for •••• 1111", m("OTP 482913 for 4111 1111 1111 1111"))
        assertEquals("Ref 1234567890123 paid", m("Ref 1234567890123 paid")) // fails Luhn
    }

    @Test fun `luhn`() {
        assertTrue(SensitiveMask.luhn("4111111111111111"))
        assertFalse(SensitiveMask.luhn("4111111111111112"))
    }

    @Test fun `what is kept is never masked - only the upload is`() {
        val raw = RawNotification("p", "Parking zone code is 4521", "OTP 482913", null, null, null)
        val kept = RedactionRules(RedactionConfig(emptySet(), emptyList(), captureBody = true)).apply(raw) as RedactedResult.Kept
        assertEquals("Parking zone code is 4521", kept.title)
        assertEquals("OTP 482913", kept.text)
    }
}
