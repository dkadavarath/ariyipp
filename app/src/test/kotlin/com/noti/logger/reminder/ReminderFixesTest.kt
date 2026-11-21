package com.noti.logger.reminder

import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.BillOccurrenceEntity.Companion.EDITED_AMOUNT
import com.noti.logger.data.BillOccurrenceEntity.Companion.EDITED_DUE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The reminder fixes R2-R9: one test group per finding. */
class ReminderFixesTest {

    // ---- R4: month-end bills keep their day ----

    @Test fun `a bill on the 31st comes back to the 31st after February`() {
        val jan31 = LocalDate.of(2026, 1, 31)
        val today = LocalDate.of(2026, 1, 1)
        var due = ReminderPolicy.nextManualDue(jan31, Recurrence.MONTHLY, 1, 0, null, today)!!
        val seen = mutableListOf(due)
        repeat(3) { due = ReminderPolicy.nextManualDue(jan31, Recurrence.MONTHLY, 1, 0, due, today)!!; seen += due }
        assertEquals(listOf(jan31, LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)), seen)
    }

    @Test fun `nth recurrence counts from the start`() {
        assertEquals(LocalDate.of(2026, 3, 31), ReminderPolicy.nthRecurrence(LocalDate.of(2026, 1, 31), Recurrence.MONTHLY, 1, 2))
        assertEquals(LocalDate.of(2028, 2, 29), ReminderPolicy.nthRecurrence(LocalDate.of(2024, 2, 29), Recurrence.YEARLY, 2, 2))
    }

    // ---- R5: paying late doesn't skip the next cycle ----

    @Test fun `a bill paid late still yields the following cycle, overdue`() {
        // Due 5 Jan, paid 8 Feb: February's bill (5 Feb) is already overdue but must appear.
        val start = LocalDate.of(2026, 1, 5)
        val next = ReminderPolicy.nextManualDue(start, Recurrence.MONTHLY, 1, 0, after = start, today = LocalDate.of(2026, 2, 8))
        assertEquals(LocalDate.of(2026, 2, 5), next)
    }

    @Test fun `an idle rule yields only the latest overdue cycle, not a backlog`() {
        val start = LocalDate.of(2026, 1, 5)
        val next = ReminderPolicy.nextManualDue(start, Recurrence.MONTHLY, 1, 0, after = start, today = LocalDate.of(2026, 6, 10))
        assertEquals(LocalDate.of(2026, 6, 5), next)
    }

    @Test fun `with nothing recorded yet, a past start still gives the next real date`() {
        val next = ReminderPolicy.nextManualDue(LocalDate.of(2026, 1, 5), Recurrence.MONTHLY, 1, 0, null, LocalDate.of(2026, 6, 10))
        assertEquals(LocalDate.of(2026, 7, 5), next)
    }

    // ---- R7: common date shapes ----

    private val ref = LocalDate.of(2026, 1, 10)

    @Test fun `single-digit days and months parse`() {
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("7 Feb 2026", null, ref))
        assertEquals(LocalDate.of(2027, 1, 5), ReminderPolicy.parseDate("5/1/2027", null, ref))
        assertEquals(LocalDate.of(2026, 3, 9), ReminderPolicy.parseDate("9-3-26", null, ref))
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("Feb 7, 2026", null, ref))
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("7 February 2026", null, ref))
    }

    @Test fun `two-digit forms still parse as before`() {
        assertEquals(LocalDate.of(2026, 10, 6), ReminderPolicy.parseDate("06-OCT-26", null, ref))
        assertEquals(LocalDate.of(2026, 1, 5), ReminderPolicy.parseDate("05/01/2026", null, ref))
        assertEquals(LocalDate.of(2026, 2, 7), ReminderPolicy.parseDate("07-Feb", null, ref))
    }

    @Test fun `lower-case am and pm parse`() {
        val now = LocalDateTime.of(2026, 1, 10, 9, 0)
        assertEquals(LocalTime.of(14, 30), ReminderPolicy.parseDateTime("02:30 pm", null, now)!!.toLocalTime())
        assertEquals(LocalTime.of(14, 30), ReminderPolicy.parseDateTime("2:30pm", null, now)!!.toLocalTime())
        assertEquals(LocalTime.of(9, 45), ReminderPolicy.parseDateTime("9:45", null, now)!!.toLocalTime())
        assertEquals(LocalDateTime.of(2026, 1, 12, 18, 5), ReminderPolicy.parseDateTime("12/1/2026 6:05 pm", null, now))
    }

    // ---- R8: spacing works in time order ----

    @Test fun `spacing ignores input order and keeps each warning on its day`() {
        val day1 = LocalDateTime.of(2026, 1, 1, 9, 0)
        val day5 = LocalDateTime.of(2026, 1, 5, 9, 0)
        // As the scheduler lists them - by due date - a 7-day lead lands before a 2-day one.
        val spaced = ReminderPolicy.spaceOut(listOf(day5, day1, day1), 5)
        assertEquals(listOf(day5, day1, day1.plusMinutes(5)), spaced)
    }

    // ---- R3: a parking alarm rings late rather than never ----

    @Test fun `late parking reminder rings now while the session is still running`() {
        val now = 1_000_000L
        assertEquals(now + ReminderPolicy.PARKING_LATE_RING_DELAY_MS, ReminderPolicy.parkingFireAt(now - 60_000, now + 120_000, now))
        assertEquals(now + 60_000, ReminderPolicy.parkingFireAt(now + 60_000, now + 120_000, now))
        assertNull("expired", ReminderPolicy.parkingFireAt(now - 60_000, now - 1, now))
        assertNull("no expiry", ReminderPolicy.parkingFireAt(now - 60_000, 0, now))
        assertNull("already rang", ReminderPolicy.parkingFireAt(now - 60_000, now + 120_000, now, rangForMs = now - 60_000))
    }

    // ---- R2: parking history doesn't come back; edits survive a rescan ----

    @Test fun `old tickets don't open sessions`() {
        val now = 100L * 3_600_000L
        assertFalse(com.noti.logger.reminder.ParkingTracker.mayStart(now - 1, now - 3_600_000L, now))
        assertTrue(com.noti.logger.reminder.ParkingTracker.mayStart(now + 600_000, now - 60_000, now))
        assertFalse(com.noti.logger.reminder.ParkingTracker.mayStart(null, now - 13 * 3_600_000L, now))
        assertTrue(com.noti.logger.reminder.ParkingTracker.mayStart(null, now - 60_000, now))
    }

    private fun bill(amount: Double?, due: Long, edited: Int = 0) =
        BillOccurrenceEntity(id = 1, name = "Card", dueAt = due, amount = amount, amountRaw = "$amount", editedFields = edited)

    @Test fun `a rescan keeps edited fields and fills untouched ones`() {
        val mine = bill(amount = null, due = 500, edited = EDITED_DUE)
        val scanned = bill(amount = 120.0, due = 900)
        val merged = BillOccurrenceEntity.mergeRescan(mine, scanned)
        assertEquals(500L, merged.dueAt)       // my date
        assertEquals(120.0, merged.amount)     // filled in from the statement
        assertEquals(EDITED_DUE, merged.editedFields)
    }

    @Test fun `editing marks only what changed`() {
        val before = bill(amount = 100.0, due = 500)
        val after = before.copy(amount = 110.0)
        assertEquals(EDITED_AMOUNT, BillOccurrenceEntity.editedMask(before, after))
    }

    // ---- R9: dates no time zone can move ----

    @Test fun `stored dates round trip with no zone involved`() {
        val d = LocalDate.of(2026, 3, 31)
        assertEquals(d, DueDates.fromMs(DueDates.toMs(d)))
        assertEquals(LocalDate.of(1969, 12, 31), DueDates.fromMs(DueDates.toMs(LocalDate.of(1969, 12, 31))))
    }

    private val india = ZoneId.of("Asia/Kolkata")
    private val uae = ZoneId.of("Asia/Dubai")
    private fun localMidnight(d: LocalDate, z: ZoneId) = d.atStartOfDay(z).toInstant().toEpochMilli()

    @Test fun `an Indian bill migrated in the UAE keeps its date`() {
        val feb7 = LocalDate.of(2026, 2, 7)
        // Read naively in Dubai, India's midnight is 22:30 on the 6th.
        assertEquals(LocalDate.of(2026, 2, 6), Instant.ofEpochMilli(localMidnight(feb7, india)).atZone(uae).toLocalDate())
        assertEquals(feb7, DueDates.fromLegacy(localMidnight(feb7, india), listOf(india), uae))
    }

    @Test fun `zones that disagree fall back to the nearest midnight`() {
        val feb7 = LocalDate.of(2026, 2, 7)
        val eur = listOf(ZoneId.of("Europe/Lisbon"), ZoneId.of("Europe/Berlin"), ZoneId.of("Europe/Helsinki"))
        assertEquals(feb7, DueDates.fromLegacy(localMidnight(feb7, ZoneId.of("Europe/Berlin")), eur, uae))
        assertEquals(feb7, DueDates.fromLegacy(localMidnight(feb7, india), emptyList(), uae))
        assertEquals(feb7, DueDates.fromLegacy(localMidnight(feb7, uae), emptyList(), india))
    }

    @Test fun `no date stays no date`() {
        assertEquals(0L, DueDates.legacyToMs(0L, listOf(india), uae))
    }
}
