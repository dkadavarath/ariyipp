package com.noti.logger.reminder

import com.noti.logger.reminder.PatternBuilder.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternBuilderTest {

    private val statement =
        "Dear Customer, your HDFC Bank Credit Card statement for Jan 2026 is generated. " +
            "Total Amount Due INR 12,480.50. Payment due by 07-Feb-2026. Ignore if paid."

    private val ticket =
        "Parking started. Zone: ZONE-4B North Lot. Your session is valid until 14:30. Renew in app."

    /** Selects the first occurrence of [text], the way tapping it in the sample would. */
    private fun build(sample: String, text: String, field: Field): PatternBuilder.Result? {
        val at = sample.indexOf(text)
        require(at >= 0) { "sample does not contain $text" }
        return PatternBuilder.forSelection(sample, at, at + text.length, field)
    }

    /** What the generated pattern actually pulls out of the sample. */
    private fun captured(result: PatternBuilder.Result?, sample: String): String? =
        ReminderExtractor.capture(result?.pattern, sample)

    // ---- The whole point: what it builds must extract what was pointed at ----

    @Test fun `an amount selection captures that amount`() {
        val r = build(statement, "12,480.50", Field.AMOUNT)
        assertNotNull(r)
        assertEquals("12,480.50", captured(r, statement))
    }

    @Test fun `the amount pattern generalises to next month's value`() {
        // The value must not be baked in - that is the difference between a pattern and a bookmark.
        val r = build(statement, "12,480.50", Field.AMOUNT)!!
        val nextMonth = statement.replace("12,480.50", "9,315.00")
        assertEquals("9,315.00", ReminderExtractor.capture(r.pattern, nextMonth))
    }

    @Test fun `a due date selection captures the date and infers its format`() {
        val r = build(statement, "07-Feb-2026", Field.DATE)
        assertNotNull(r)
        assertEquals("07-Feb-2026", captured(r, statement))
        assertEquals("dd-MMM-yyyy", r!!.dateFormat)
    }

    @Test fun `the date pattern generalises to a different date`() {
        val r = build(statement, "07-Feb-2026", Field.DATE)!!
        val later = statement.replace("07-Feb-2026", "28-Mar-2026")
        assertEquals("28-Mar-2026", ReminderExtractor.capture(r.pattern, later))
    }

    @Test fun `a currency code generalises rather than being pinned`() {
        val r = build(statement, "INR", Field.CURRENCY)
        assertNotNull(r)
        assertEquals("INR", captured(r, statement))
        val aed = statement.replace("INR", "AED")
        assertEquals("AED", ReminderExtractor.capture(r!!.pattern, aed))
    }

    @Test fun `an identify selection matches the message without capturing`() {
        val r = build(statement, "statement for Jan 2026 is generated", Field.IDENTIFY)
        assertNotNull(r)
        assertTrue(ReminderExtractor.matches(r!!.pattern, statement))
    }

    // ---- Disambiguation: the anchor has to do real work ----

    @Test fun `it anchors well enough to skip an earlier lookalike`() {
        // Two dates in one message. Pointing at the second must not produce a pattern that grabs
        // the first, which a bare shape pattern would.
        val twoDates = "Statement dated 10-Jan-2026. Payment due by 07-Feb-2026."
        val at = twoDates.indexOf("07-Feb-2026")
        val r = PatternBuilder.forSelection(twoDates, at, at + "07-Feb-2026".length, Field.DATE)
        assertNotNull(r)
        assertEquals("07-Feb-2026", ReminderExtractor.capture(r!!.pattern, twoDates))
    }

    @Test fun `it anchors past an earlier number to reach the right one`() {
        val twoNumbers = "Card ending 4321. Total Amount Due INR 12,480.50."
        val at = twoNumbers.indexOf("12,480.50")
        val r = PatternBuilder.forSelection(twoNumbers, at, at + "12,480.50".length, Field.AMOUNT)
        assertNotNull(r)
        assertEquals("12,480.50", ReminderExtractor.capture(r!!.pattern, twoNumbers))
    }

    // ---- Parking ----

    @Test fun `a zone selection captures a multi-word zone`() {
        val r = build(ticket, "ZONE-4B North Lot", Field.ZONE)
        assertNotNull(r)
        assertEquals("ZONE-4B North Lot", captured(r, ticket))
    }

    @Test fun `an expiry time selection captures the time and infers HH mm`() {
        val r = build(ticket, "14:30", Field.EXPIRY)
        assertNotNull(r)
        assertEquals("14:30", captured(r, ticket))
        assertEquals("HH:mm", r!!.dateFormat)
    }

    @Test fun `a zone pattern generalises to a different bay`() {
        val r = build(ticket, "ZONE-4B North Lot", Field.ZONE)!!
        val other = ticket.replace("ZONE-4B North Lot", "BAY-12 Marina")
        assertEquals("BAY-12 Marina", ReminderExtractor.capture(r.pattern, other))
    }

    // ---- Whitespace, because a notification and an SMS differ in exactly this ----

    @Test fun `patterns survive the same text laid out over lines`() {
        val r = build(statement, "12,480.50", Field.AMOUNT)!!
        val asNotification = statement.replace("Total Amount Due", "Total Amount\nDue")
        assertEquals("12,480.50", ReminderExtractor.capture(r.pattern, asNotification))
    }

    // ---- Refusing rather than guessing ----

    @Test fun `a blank or impossible selection yields nothing`() {
        assertNull(PatternBuilder.forSelection(statement, 5, 5, Field.AMOUNT))
        assertNull(PatternBuilder.forSelection(statement, -1, 4, Field.AMOUNT))
        assertNull(PatternBuilder.forSelection(statement, 0, 9999, Field.AMOUNT))
    }

    @Test fun `selecting words as an amount yields nothing rather than a bad pattern`() {
        // "Dear Customer" is not an amount; offering a pattern for it would be worse than declining.
        assertNull(build(statement, "Dear Customer", Field.AMOUNT))
    }

    // ---- Date-format inference on its own ----

    @Test fun `common date and time shapes are recognised`() {
        assertEquals("dd/MM/yyyy", PatternBuilder.dateFormatOf("07/02/2026"))
        assertEquals("dd-MM-yyyy", PatternBuilder.dateFormatOf("07-02-2026"))
        assertEquals("yyyy-MM-dd", PatternBuilder.dateFormatOf("2026-02-07"))
        assertEquals("dd-MMM-yyyy", PatternBuilder.dateFormatOf("07-Feb-2026"))
        assertEquals("dd MMM yy", PatternBuilder.dateFormatOf("07 Feb 26"))
        assertEquals("HH:mm", PatternBuilder.dateFormatOf("14:30"))
        assertEquals("dd/MM/yyyy HH:mm", PatternBuilder.dateFormatOf("15/01/2026 14:30"))
    }

    @Test fun `an unrecognised shape returns no format rather than a wrong one`() {
        // The extractor already falls back to trying common formats, so a guess here would only
        // stop that fallback from running.
        assertNull(PatternBuilder.dateFormatOf("sometime next Tuesday"))
        assertNull(PatternBuilder.dateFormatOf("Feb 2026"))
    }

    // ---- ICICI statement SMS, end to end: build each field from a selection, then extract ----

    private val icici = "ICICI Bank Credit Card XX4321 Statement is sent to user@example.com. Total of Rs 18,402.75 " +
        "or minimum of Rs 1,170.00 is due by 06-OCT-26."

    private fun sel(field: PatternBuilder.Field, text: String): PatternBuilder.Result {
        val i = icici.indexOf(text)
        return PatternBuilder.forSelection(icici, i, i + text.length, field)
            ?: throw AssertionError("no pattern for '$text'")
    }

    @Test fun `amount selected with its Rs prefix narrows to the number`() {
        val r = sel(PatternBuilder.Field.AMOUNT, "Rs 18,402.75")
        assertEquals("18,402.75", ReminderExtractor.capture(r.pattern, icici))
    }

    @Test fun `icici statement reads amount, currency and upper-case date`() {
        val amount = sel(PatternBuilder.Field.AMOUNT, "18,402.75")
        val currency = sel(PatternBuilder.Field.CURRENCY, "Rs")
        val date = sel(PatternBuilder.Field.DATE, "06-OCT-26")
        assertEquals("dd-MMM-yy", date.dateFormat)
        val bill = ReminderExtractor.extractBill(
            text = icici, matchPattern = "ICICI Bank Credit Card", amountPattern = amount.pattern,
            currencyPattern = currency.pattern, datePattern = date.pattern, dateFormat = date.dateFormat,
            today = java.time.LocalDate.of(2026, 9, 20),
        )!!
        assertEquals(18402.75, bill.amount!!, 0.001)
        assertEquals("Rs", bill.currency)
        assertEquals(java.time.LocalDate.of(2026, 10, 6), bill.dueDate)
    }
}
