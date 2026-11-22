package com.noti.logger.reminder

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dates that arrive without a year, which plenty of banks send: "Payment due by 07-Feb".
 *
 * The year has to be supplied from the message's own date, and the interesting part is which year.
 * Naively taking the current one turns a December statement saying "due 05-Jan" into a date eleven
 * months in the past - a bill that then looks long overdue and never schedules a reminder.
 */
class YearlessDateTest {

    private val feb1 = LocalDate.of(2026, 2, 1)

    @Test fun `a year-less date takes the current year`() {
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("07-Feb", null, feb1))
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("07 Feb", null, feb1))
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("07/02", null, feb1))
    }

    @Test fun `an explicit year-less format is honoured`() {
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("07-Feb", "dd-MMM", feb1))
    }

    @Test fun `a December statement due in January rolls to next year`() {
        // The case that makes this worth doing: current-year would put it 11 months in the past.
        val dec20 = LocalDate.of(2026, 12, 20)
        assertEquals(LocalDate.of(2027, 1, 5), ReminderPolicy.parseDate("05-Jan", null, dec20))
    }

    @Test fun `a recently passed due date stays in the past rather than jumping a year`() {
        // A bill that went overdue three days ago must keep reading as overdue. Rolling it forward
        // would hide the one bill the user most needs to see.
        val feb10 = LocalDate.of(2026, 2, 10)
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("07-Feb", null, feb10))
    }

    @Test fun `the roll happens only past the grace window`() {
        val ref = LocalDate.of(2026, 6, 1)
        val justInside = ref.minusDays(ReminderPolicy.YEARLESS_BACKDATE_GRACE_DAYS - 1)
        val wellOutside = ref.minusDays(ReminderPolicy.YEARLESS_BACKDATE_GRACE_DAYS + 30)
        val insideText = "%02d-%s".format(justInside.dayOfMonth, justInside.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() })
        val outsideText = "%02d-%s".format(wellOutside.dayOfMonth, wellOutside.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() })
        assertEquals(justInside, ReminderPolicy.parseDate(insideText, null, ref))
        assertEquals(wellOutside.plusYears(1), ReminderPolicy.parseDate(outsideText, null, ref))
    }

    @Test fun `dated messages are still parsed by the full patterns`() {
        // The year-less patterns are tried last, so nothing that carries a year is caught by them.
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("07-Feb-2026", null, feb1))
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("07/02/2026", null, feb1))
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("2026-02-07", null, feb1))
    }

    @Test fun `a year-less parking expiry works the same way`() {
        val now = LocalDateTime.of(2026, 2, 1, 9, 0)
        assertEquals(LocalDateTime.of(2026, 2, 7, 14, 30), ReminderPolicy.parseDateTime("07/02 14:30", null, now))
    }

    @Test fun `the pattern builder infers a year-less format`() {
        assertEquals("dd-MMM", PatternBuilder.dateFormatOf("07-Feb"))
        assertEquals("dd MMM", PatternBuilder.dateFormatOf("07 Feb"))
        assertEquals("dd/MM", PatternBuilder.dateFormatOf("07/02"))
    }

    @Test fun `selecting a year-less date builds a working pattern and format`() {
        val sms = "Your statement is generated. Total INR 900.00 due by 07-Feb. Ignore if paid."
        val at = sms.indexOf("07-Feb")
        val r = PatternBuilder.forSelection(sms, at, at + "07-Feb".length, PatternBuilder.Field.DATE)!!
        assertEquals("dd-MMM", r.dateFormat)
        val captured = ReminderExtractor.capture(r.pattern, sms)
        assertEquals("07-Feb", captured)
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate(captured, r.dateFormat, feb1))
    }

    @Test fun `nonsense still parses to nothing`() {
        assertNull(ReminderPolicy.parseDate("not a date", null, feb1))
        assertNull(ReminderPolicy.parseDate("", null, feb1))
    }
}
