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
