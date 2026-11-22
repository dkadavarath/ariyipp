package com.noti.logger.reminder

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Recurrence with an interval, and the two shapes a manual bill can take: a date that repeats, and
 * a bill raised on one date and due some days later.
 */
class RecurrenceTest {

    private val jan1 = LocalDate.of(2026, 1, 1)

    // ---- Stepping ----

    @Test fun `intervals step by whole units`() {
        assertEquals(LocalDate.of(2026, 1, 6), ReminderPolicy.nextRecurrence(jan1, Recurrence.DAILY, 5))
        assertEquals(LocalDate.of(2026, 1, 29), ReminderPolicy.nextRecurrence(jan1, Recurrence.DAILY, 28))
        assertEquals(LocalDate.of(2026, 1, 22), ReminderPolicy.nextRecurrence(jan1, Recurrence.WEEKLY, 3))
        assertEquals(LocalDate.of(2026, 4, 1), ReminderPolicy.nextRecurrence(jan1, Recurrence.MONTHLY, 3))
        assertEquals(LocalDate.of(2029, 1, 1), ReminderPolicy.nextRecurrence(jan1, Recurrence.YEARLY, 3))
    }

    @Test fun `a monthly step clamps rather than spilling into the next month`() {
        // 31 January plus a month is 28 February, not 3 March.
        assertEquals(
            LocalDate.of(2026, 2, 28),
            ReminderPolicy.nextRecurrence(LocalDate.of(2026, 1, 31), Recurrence.MONTHLY, 1),
        )
    }

    @Test fun `never does not move and a zero interval is treated as one`() {
        assertEquals(jan1, ReminderPolicy.nextRecurrence(jan1, Recurrence.NEVER, 3))
        assertEquals(LocalDate.of(2026, 1, 2), ReminderPolicy.nextRecurrence(jan1, Recurrence.DAILY, 0))
    }

    // ---- A date that simply repeats ----

    @Test fun `a repeating date yields itself first, then each following period`() {
        val first = ReminderPolicy.nextManualDue(jan1, Recurrence.MONTHLY, 1, 0, after = null, today = jan1)
        assertEquals(jan1, first)
        val second = ReminderPolicy.nextManualDue(jan1, Recurrence.MONTHLY, 1, 0, after = first, today = jan1)
        assertEquals(LocalDate.of(2026, 2, 1), second)
    }

    @Test fun `a rule set up months ago yields the next real date, not a backlog`() {
        // Set up in January, first opened in April: the answer is the next occurrence, not four.
        val today = LocalDate.of(2026, 4, 10)
        val due = ReminderPolicy.nextManualDue(jan1, Recurrence.MONTHLY, 1, 0, after = null, today = today)
        assertEquals(LocalDate.of(2026, 5, 1), due)
    }

    @Test fun `every 28 days keeps its own cycle rather than drifting to a month`() {
        var due = ReminderPolicy.nextManualDue(jan1, Recurrence.DAILY, 28, 0, null, jan1)!!
        assertEquals(jan1, due)
        due = ReminderPolicy.nextManualDue(jan1, Recurrence.DAILY, 28, 0, due, jan1)!!
        assertEquals(LocalDate.of(2026, 1, 29), due)
        due = ReminderPolicy.nextManualDue(jan1, Recurrence.DAILY, 28, 0, due, jan1)!!
        assertEquals(LocalDate.of(2026, 2, 26), due)
    }

    // ---- Raised on one date, due later ----

    @Test fun `bill raised on the 28th and due ten days later`() {
        // The case asked for: statement on the 28th of every month, payment due 10 days after.
        val anchor = LocalDate.of(2026, 1, 28)
        val first = ReminderPolicy.nextManualDue(anchor, Recurrence.MONTHLY, 1, 10, null, anchor)
        assertEquals(LocalDate.of(2026, 2, 7), first)
        val second = ReminderPolicy.nextManualDue(anchor, Recurrence.MONTHLY, 1, 10, first, anchor)
        assertEquals(LocalDate.of(2026, 3, 10), second)
    }

    @Test fun `the anchor recurs, not the due date`() {
        // Stepping the due date directly would drift: Feb 7 + 1 month = Mar 7, but the bill is
        // raised on the 28th, so the real due date is Mar 10. Anchoring keeps them in step.
        val anchor = LocalDate.of(2026, 1, 28)
        var due = ReminderPolicy.nextManualDue(anchor, Recurrence.MONTHLY, 1, 10, null, anchor)!!
        due = ReminderPolicy.nextManualDue(anchor, Recurrence.MONTHLY, 1, 10, due, anchor)!!
        assertEquals(LocalDate.of(2026, 3, 10), due)
        due = ReminderPolicy.nextManualDue(anchor, Recurrence.MONTHLY, 1, 10, due, anchor)!!
        assertEquals(LocalDate.of(2026, 4, 7), due)
    }

    // ---- One-offs ----

    @Test fun `a one-off appears once, even if its date has passed`() {
        // An overdue one-off must still show up rather than being skipped for being late.
        val past = LocalDate.of(2026, 1, 5)
        val today = LocalDate.of(2026, 2, 1)
        assertEquals(past, ReminderPolicy.nextManualDue(past, Recurrence.NEVER, 1, 0, null, today))
    }

    @Test fun `a one-off does not come back once recorded`() {
        val past = LocalDate.of(2026, 1, 5)
        assertNull(ReminderPolicy.nextManualDue(past, Recurrence.NEVER, 1, 0, after = past, today = past))
    }
}
