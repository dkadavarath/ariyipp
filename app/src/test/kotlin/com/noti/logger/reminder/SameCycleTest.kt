package com.noti.logger.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameCycleTest {
    @Test fun `a re-sent reminder with the same amount is the same bill`() =
        assertTrue(ReminderScanner.sameCycle(18402.75, 18402.75))

    @Test fun `two cards due the same day are two bills`() =
        assertFalse(ReminderScanner.sameCycle(18402.75, 3120.00))

    @Test fun `a missing amount on either side still matches`() {
        assertTrue(ReminderScanner.sameCycle(null, 50.0))
        assertTrue(ReminderScanner.sameCycle(50.0, null))
    }
}

class DotGroupedAmountTest {
    @Test fun `several dots are grouping`() {
        org.junit.Assert.assertEquals(1234567.0, ReminderPolicy.parseAmount("1.234.567")!!, 0.0)
        org.junit.Assert.assertEquals(1234567.89, ReminderPolicy.parseAmount("EUR 1.234.567,89")!!, 0.001)
    }

    @Test fun `a single dot stays a decimal point`() {
        org.junit.Assert.assertEquals(12.48, ReminderPolicy.parseAmount("KWD 12.480")!!, 0.0001)
        org.junit.Assert.assertEquals(18402.75, ReminderPolicy.parseAmount("Rs.18,402.75")!!, 0.001)
    }
}

class MentionsAmountTest {
    private fun m(text: String, amount: Double?) = ReminderScanner.mentionsAmount(text, amount)

    @Test fun `a reminder naming the total next to the minimum is the same bill`() =
        assertTrue(m("Min due Rs 920.00 of total Rs 18,402.75 due by 5-Mar-2027", 18402.75))

    @Test fun `another card's statement doesn't name this card's amount`() =
        assertFalse(m("Statement card 9876: total Rs 340.50 due by 5-Mar-2027", 1200.0))

    @Test fun `dates and times aren't amounts`() {
        assertFalse(m("due by 5-Mar-2027 10:30", 2027.0))
        assertFalse(m("due on 05/03/2027", 3.0))
        assertFalse(m("due on the 5th, Rs 999", 5.0))
    }

    @Test fun `a sentence's full stop doesn't break the number`() =
        assertTrue(m("Total due is Rs 1,200.00.", 1200.0))

    @Test fun `no amount on file mentions nothing`() = assertFalse(m("Rs 100", null))

    @Test fun `card digits, limits and references aren't amounts`() {
        assertFalse(m("Statement for card ending 1500: AED 2,300 due on the 5th", 1500.0))
        assertFalse(m("Card XX1500 statement: AED 2,300", 1500.0))
        assertFalse(m("Total AED 2,300. Credit limit 15000", 15000.0))
        assertFalse(m("Ref no. 4821 - AED 2,300 due", 4821.0))
        assertTrue(m("Min due AED 115 of total AED 1500", 1500.0))
        // Another card's available limit equal to this card's bill is not this bill.
        assertFalse(m("Statement: AED 2,300 due on the 5th. Available limit AED 1,500.00", 1500.0))
        assertFalse(m("Balance 1500 as of today", 1500.0))
    }
}
