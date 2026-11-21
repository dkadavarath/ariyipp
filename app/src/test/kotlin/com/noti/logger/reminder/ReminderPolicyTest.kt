package com.noti.logger.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderPolicyTest {

    private val jan15 = LocalDate.of(2026, 1, 15)

    // ---- Amounts ----

    @Test fun `plain and grouped amounts parse`() {
        assertEquals(12480.0, ReminderPolicy.parseAmount("12,480")!!, 0.001)
        assertEquals(12480.5, ReminderPolicy.parseAmount("12,480.50")!!, 0.001)
        assertEquals(499.0, ReminderPolicy.parseAmount("499")!!, 0.001)
    }

    @Test fun `currency symbols and spacing are stripped`() {
        assertEquals(1234.56, ReminderPolicy.parseAmount("INR 1,234.56")!!, 0.001)
        assertEquals(1234.56, ReminderPolicy.parseAmount("₹ 1,234.56")!!, 0.001)
        assertEquals(50.0, ReminderPolicy.parseAmount("AED 50.00")!!, 0.001)
    }

    @Test fun `european grouping is read the other way round`() {
        // "1.234,56" is 1234.56, not 1.234 - getting this backwards silently changes a bill by 1000x.
        assertEquals(1234.56, ReminderPolicy.parseAmount("1.234,56")!!, 0.001)
    }

    @Test fun `an unreadable amount is null, never zero`() {
        // A bill for 0.00 and a bill whose amount we failed to read must stay distinguishable.
        assertNull(ReminderPolicy.parseAmount(null))
        assertNull(ReminderPolicy.parseAmount(""))
        assertNull(ReminderPolicy.parseAmount("no digits here"))
    }

    // ---- Dates ----

    @Test fun `an explicit format wins`() {
        assertEquals(LocalDate.of(2026, 9, 28), ReminderPolicy.parseDate("28-Sep-2026", "dd-MMM-yyyy"))
        assertEquals(LocalDate.of(2026, 3, 4), ReminderPolicy.parseDate("04/03/2026", "dd/MM/yyyy"))
    }

    @Test fun `common shapes parse with no format given`() {
        assertEquals(LocalDate.of(2026, 9, 28), ReminderPolicy.parseDate("28/09/2026", null))
        assertEquals(LocalDate.of(2026, 9, 28), ReminderPolicy.parseDate("2026-09-28", null))
        assertEquals(LocalDate.of(2026, 9, 28), ReminderPolicy.parseDate("28 Sep 2026", null))
    }

    @Test fun `a bare day of month means the next such day`() {
        assertEquals(LocalDate.of(2026, 1, 20), ReminderPolicy.parseDate("20", null, jan15))
        // Already past this month, so next month.
        assertEquals(LocalDate.of(2026, 2, 10), ReminderPolicy.parseDate("10", null, jan15))
    }

    @Test fun `a wrong format falls through to the common shapes rather than failing`() {
        assertEquals(LocalDate.of(2026, 9, 28), ReminderPolicy.parseDate("28/09/2026", "dd-MMM-yyyy"))
    }

    @Test fun `unparseable dates are null`() {
        assertNull(ReminderPolicy.parseDate("sometime soon", null))
        assertNull(ReminderPolicy.parseDate(null, "dd/MM/yyyy"))
    }

    // ---- Times, for parking ----

    @Test fun `a bare time is anchored to today`() {
        val now = LocalDateTime.of(2026, 1, 15, 10, 0)
        assertEquals(LocalDateTime.of(2026, 1, 15, 14, 30), ReminderPolicy.parseDateTime("14:30", "HH:mm", now))
    }

    @Test fun `a bare time already past rolls to tomorrow`() {
        // A ticket bought at 23:50 valid until 00:30 means tomorrow, not fourteen hours ago.
        val now = LocalDateTime.of(2026, 1, 15, 23, 50)
        assertEquals(LocalDateTime.of(2026, 1, 16, 0, 30), ReminderPolicy.parseDateTime("00:30", "HH:mm", now))
    }

    @Test fun `a full date and time parses`() {
        assertEquals(
            LocalDateTime.of(2026, 1, 15, 14, 30),
            ReminderPolicy.parseDateTime("15/01/2026 14:30", null),
        )
    }

    // ---- Due dates ----

    @Test fun `constant mode is the same day each month`() {
        assertEquals(
            LocalDate.of(2026, 1, 20),
            ReminderPolicy.dueDate(DueMode.CONSTANT, jan15, constantDay = 20),
        )
        assertEquals(
            LocalDate.of(2026, 2, 10),
            ReminderPolicy.dueDate(DueMode.CONSTANT, jan15, constantDay = 10),
        )
    }

    @Test fun `constant mode clamps into short months`() {
        // "The 31st" in February has to mean month-end, not roll into March.
        assertEquals(
            LocalDate.of(2026, 2, 28),
            ReminderPolicy.dueDate(DueMode.CONSTANT, LocalDate.of(2026, 2, 1), constantDay = 31),
        )
    }

    @Test fun `calculated mode is the statement pattern`() {
        // Statement on the 10th, due 28 days later. A statement dated the 12th belongs to this
        // month's anchor, so due 7 Feb - not next month's.
        assertEquals(
            LocalDate.of(2026, 2, 7),
            ReminderPolicy.dueDate(DueMode.CALCULATED, LocalDate.of(2026, 1, 12), anchorDay = 10, offsetDays = 28),
        )
    }

    @Test fun `calculated mode counts from the previous anchor when this month's hasn't happened`() {
        // A statement on the 5th can't belong to the 10th of the same month - that's still ahead.
        assertEquals(
            LocalDate.of(2026, 1, 7),
            ReminderPolicy.dueDate(DueMode.CALCULATED, LocalDate.of(2026, 1, 5), anchorDay = 10, offsetDays = 28),
        )
    }

    @Test fun `from-message mode passes the extracted date straight through`() {
        val extracted = LocalDate.of(2026, 5, 3)
        assertEquals(extracted, ReminderPolicy.dueDate(DueMode.FROM_MESSAGE, jan15, extracted = extracted))
        assertNull(ReminderPolicy.dueDate(DueMode.FROM_MESSAGE, jan15, extracted = null))
    }

    @Test fun `recurrences step by the right unit`() {
        assertEquals(LocalDate.of(2026, 1, 22), ReminderPolicy.nextRecurrence(jan15, Recurrence.WEEKLY))
        assertEquals(LocalDate.of(2026, 2, 15), ReminderPolicy.nextRecurrence(jan15, Recurrence.MONTHLY))
        assertEquals(LocalDate.of(2027, 1, 15), ReminderPolicy.nextRecurrence(jan15, Recurrence.YEARLY))
    }

    // ---- Firing times ----

    @Test fun `a bill reminds the day before at the chosen time`() {
        assertEquals(
            LocalDateTime.of(2026, 1, 14, 9, 0),
            ReminderPolicy.billReminderAt(jan15, daysBefore = 1, hour = 9, minute = 0),
        )
    }

    @Test fun `parking reminds ahead of expiry by the lead time`() {
        val expiry = LocalDateTime.of(2026, 1, 15, 14, 30)
        assertEquals(LocalDateTime.of(2026, 1, 15, 14, 20), ReminderPolicy.parkingReminderAt(expiry, 10))
        assertEquals(expiry, ReminderPolicy.parkingReminderAt(expiry, 0))
    }

    @Test fun `collisions are spaced out in order`() {
        val nine = LocalDateTime.of(2026, 1, 14, 9, 0)
        val spaced = ReminderPolicy.spaceOut(listOf(nine, nine, nine), 5)
        assertEquals(
            listOf(nine, nine.plusMinutes(5), nine.plusMinutes(10)),
            spaced,
        )
    }

    @Test fun `a lone reminder fires exactly when asked`() {
        val nine = LocalDateTime.of(2026, 1, 14, 9, 0)
        assertEquals(listOf(nine), ReminderPolicy.spaceOut(listOf(nine), 5))
    }

    @Test fun `times already far enough apart are left alone`() {
        val nine = LocalDateTime.of(2026, 1, 14, 9, 0)
        val later = nine.plusHours(2)
        assertEquals(listOf(nine, later), ReminderPolicy.spaceOut(listOf(nine, later), 5))
    }

    @Test fun `epoch conversion round-trips through local time`() {
        val t = LocalDateTime.of(2026, 1, 15, 14, 30)
        assertEquals(t, ReminderPolicy.fromEpochMillis(ReminderPolicy.toEpochMillis(t)))
    }

    @Test fun `spacing off leaves everything untouched`() {
        val nine = LocalDateTime.of(2026, 1, 14, 9, 0)
        assertTrue(ReminderPolicy.spaceOut(listOf(nine, nine), 0).all { it == nine })
    }

    // ---- Real-world bank formats (ICICI: "...due by 06-OCT-26.") ----

    @Test fun `upper-case month names parse`() {
        assertEquals(LocalDate.of(2026, 10, 6), ReminderPolicy.parseDate("06-OCT-26", null, LocalDate.of(2026, 9, 20)))
        assertEquals(LocalDate.of(2026, 10, 6), ReminderPolicy.parseDate("06-OCT-26", "dd-MMM-yy", LocalDate.of(2026, 9, 20)))
    }

    @Test fun `a date from another year parses with its own year`() {
        // Read in January: the December statement's year is not the current one.
        assertEquals(LocalDate.of(2025, 12, 6), ReminderPolicy.parseDate("06-Dec-25", null, LocalDate.of(2026, 1, 2)))
        assertEquals(LocalDate.of(2025, 12, 6), ReminderPolicy.parseDate("06/12/2025", "dd/MM/yyyy", LocalDate.of(2026, 1, 2)))
        assertEquals(LocalDateTime.of(2025, 12, 31, 23, 30),
            ReminderPolicy.parseDateTime("31/12/2025 23:30", "dd/MM/yyyy HH:mm", LocalDateTime.of(2026, 1, 1, 0, 10)))
    }

    @Test fun `a trailing full stop on a captured date is ignored`() {
        assertEquals(LocalDate.of(2026, 10, 6), ReminderPolicy.parseDate("06-OCT-26.", null, LocalDate.of(2026, 9, 20)))
    }

    @Test fun `rupee prefixes and a trailing full stop don't break the amount`() {
        assertEquals(18402.75, ReminderPolicy.parseAmount("Rs 18,402.75")!!, 0.001)
        assertEquals(18402.75, ReminderPolicy.parseAmount("Rs. 18,402.75")!!, 0.001)
        assertEquals(1170.0, ReminderPolicy.parseAmount("Rs.1,170.00.")!!, 0.001)
        assertEquals(500.0, ReminderPolicy.parseAmount("INR 500")!!, 0.001)
        assertEquals(-500.0, ReminderPolicy.parseAmount("Rs.-500")!!, 0.001)
    }
}
