package com.noti.logger.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning charges on different cycles into one comparable monthly figure.
 *
 * The averaging is the whole point. Calling a weekly bill "four a month" understates it by about
 * 8%, and a 28-day cycle by more - across a dozen subscriptions that is the difference between a
 * total you can trust and one that is quietly always low.
 */
class SpendTotalsTest {

    private fun monthly(amount: Double, r: Recurrence, every: Int = 1) =
        ReminderPolicy.monthlyEquivalent(amount, r, every)

    @Test fun `a monthly charge is itself`() {
        assertEquals(12.99, monthly(12.99, Recurrence.MONTHLY), 0.001)
    }

    @Test fun `a yearly charge spreads over twelve months`() {
        assertEquals(10.0, monthly(120.0, Recurrence.YEARLY), 0.001)
    }

    @Test fun `a weekly charge averages properly rather than being called four a month`() {
        // 52.18 weeks a year / 12, not 4 - the naive figure would be 40.00 and always too low.
        assertEquals(43.48, monthly(10.0, Recurrence.WEEKLY), 0.01)
    }

    @Test fun `a daily charge uses the real year length`() {
        assertEquals(30.44, monthly(1.0, Recurrence.DAILY), 0.01)
    }

    @Test fun `intervals divide the rate`() {
        assertEquals(6.495, monthly(12.99, Recurrence.MONTHLY, 2), 0.001)   // every 2 months
        assertEquals(21.74, monthly(10.0, Recurrence.WEEKLY, 2), 0.01)      // fortnightly
        assertEquals(10.87, monthly(10.0, Recurrence.DAILY, 28), 0.01)      // a 28-day cycle
    }

    @Test fun `a 28 day cycle is not a month`() {
        // 13.04 charges a year against 12 - the gap that makes card cycles worth modelling.
        val twentyEight = monthly(100.0, Recurrence.DAILY, 28)
        val monthly = monthly(100.0, Recurrence.MONTHLY, 1)
        assertEquals(108.7, twentyEight, 0.1)
        assertEquals(100.0, monthly, 0.001)
    }

    @Test fun `a one-off contributes nothing to a recurring total`() {
        // It is a charge, not a rate; counting it as monthly spend would overstate every total.
        assertEquals(0.0, monthly(500.0, Recurrence.NEVER), 0.001)
    }

    @Test fun `a zero interval is treated as one rather than dividing by zero`() {
        assertEquals(12.99, monthly(12.99, Recurrence.MONTHLY, 0), 0.001)
    }

    // ---- Per-entry reminder lead ----

    @Test fun `an entry without its own lead uses the global one`() {
        assertEquals(1, ReminderPolicy.effectiveLead(ReminderPolicy.LEAD_FROM_GLOBAL, 1))
        assertEquals(5, ReminderPolicy.effectiveLead(ReminderPolicy.LEAD_FROM_GLOBAL, 5))
    }

    @Test fun `an entry's own lead wins, including same-day`() {
        assertEquals(7, ReminderPolicy.effectiveLead(7, 1))
        assertEquals(0, ReminderPolicy.effectiveLead(0, 3))
    }
}
