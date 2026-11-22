package com.noti.logger.reminder

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-drafted rule must read the *right* values back out of the message it was drafted from -
 * the total, not the minimum; the due date, not the statement date - through the same extractor a
 * scan uses. Real message shapes, not toy ones.
 */
class RuleAutoParserTest {

    private val today = LocalDate.of(2026, 9, 20)

    private fun read(sample: String): ReminderExtractor.BillMatch {
        val d = RuleAutoParser.draft(sample, today)
        return ReminderExtractor.extractBill(
            sample, d.matchPattern, d.amountPattern, d.currencyPattern, d.datePattern, d.dateFormat, today,
        ) ?: throw AssertionError("draft doesn't match its own sample: $d")
    }

    @Test fun `icici statement - total over minimum, upper-case date`() {
        val m = read(
            "ICICI Bank Credit Card XX4321 Statement is sent to user@example.com. Total of Rs 18,402.75 " +
                "or minimum of Rs 1,170.00 is due by 06-OCT-26."
        )
        assertEquals(18402.75, m.amount!!, 0.001)
        assertEquals("Rs", m.currency)
        assertEquals(LocalDate.of(2026, 10, 6), m.dueDate)
    }

    @Test fun `minimum first, total second - still the total`() {
        val m = read("Minimum Amount Due Rs.500.00 and Total Amount Due Rs.5,000.00 for card XX1234. Pay by 12 Dec 2026.")
        assertEquals(5000.0, m.amount!!, 0.001)
        assertEquals(LocalDate.of(2026, 12, 12), m.dueDate)
    }

    @Test fun `utility bill with currency code after the number`() {
        val m = read("Your DEWA bill for Oct is 412.50 AED. Please pay before 15/11/2026 to avoid disconnection.")
        assertEquals(412.5, m.amount!!, 0.001)
        assertEquals("AED", m.currency)
        assertEquals(LocalDate.of(2026, 11, 15), m.dueDate)
    }

    @Test fun `statement date loses to the due date`() {
        val m = read("Statement dated 01/09/2026: Total Amount Due INR 12,480.50. Payment due by 07-Feb-2027.")
        assertEquals(12480.5, m.amount!!, 0.001)
        assertEquals(LocalDate.of(2027, 2, 7), m.dueDate)
    }

    @Test fun `card number digits are not taken as the amount`() {
        val m = read("Card XX4321: bill of AED 1,250 due on 25 Oct 2026")
        assertEquals(1250.0, m.amount!!, 0.001)
    }

    @Test fun `nothing recognisable leaves the draft empty rather than wrong`() {
        val d = RuleAutoParser.draft("Your OTP is 482913. Do not share it.", today)
        assertTrue(d.amountPattern == null && d.datePattern == null && d.currencyPattern == null)
    }

    @Test fun `identify phrase is drafted`() {
        assertNotNull(RuleAutoParser.draft("Your electricity bill of AED 300 is due 5 Dec 2026", today).matchPattern)
    }

    @Test fun `operator-routed sender ids reduce to their stable core`() {
        assertEquals("ICICIB", RuleAutoParser.senderFilter("AD-ICICIB"))
        assertEquals("ICICIB", RuleAutoParser.senderFilter("VM-ICICIB-S"))
        assertEquals("DEWA", RuleAutoParser.senderFilter("DEWA"))
        assertEquals("+971501234567", RuleAutoParser.senderFilter("+971501234567"))
    }
}
