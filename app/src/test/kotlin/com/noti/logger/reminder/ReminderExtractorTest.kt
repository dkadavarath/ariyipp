package com.noti.logger.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderExtractorTest {

    /** A realistic card-statement SMS, of the shape the in-app guide teaches against. */
    private val statement =
        "Dear Customer, your HDFC Bank Credit Card statement for Jan 2026 is generated. " +
            "Total Amount Due INR 12,480.50. Payment due by 07-Feb-2026. Ignore if paid."

    private val parkingTicket =
        "Parking started. Zone: ZONE-4B North Lot. Your session is valid until 15/01/2026 14:30. Renew in app."

    private val today = LocalDate.of(2026, 1, 12)

    @Test fun `a bill statement yields amount, currency and due date`() {
        val m = ReminderExtractor.extractBill(
            text = statement,
            matchPattern = "statement.*generated",
            amountPattern = """Total Amount Due\s+(?:INR|₹)\s*([\d,]+\.?\d*)""",
            currencyPattern = """\b(INR|AED|USD)\b""",
            datePattern = """due by\s+(\d{2}-\w{3}-\d{4})""",
            dateFormat = "dd-MMM-yyyy",
            today = today,
        )
        assertNotNull(m)
        assertEquals(12480.50, m!!.amount!!, 0.001)
        assertEquals("INR", m.currency)
        assertEquals(LocalDate.of(2026, 2, 7), m.dueDate)
        assertEquals("12,480.50", m.amountRaw)
    }

    @Test fun `a message the match pattern rejects returns null`() {
        assertNull(
            ReminderExtractor.extractBill(
                text = "Your OTP is 445566", matchPattern = "statement.*generated",
                amountPattern = null, currencyPattern = null, datePattern = null, dateFormat = null,
            )
        )
    }

    @Test fun `a blank match pattern accepts everything from the source`() {
        // Legitimate for an app that only ever sends one kind of message.
        assertNotNull(
            ReminderExtractor.extractBill(
                text = "anything", matchPattern = "",
                amountPattern = null, currencyPattern = null, datePattern = null, dateFormat = null,
            )
        )
    }

    @Test fun `one broken field does not lose the others`() {
        // This is the whole reason for separate fields per value: the bank changed its date wording,
        // and the amount still comes through so the bill is usable rather than absent.
        val m = ReminderExtractor.extractBill(
            text = statement,
            matchPattern = "statement",
            amountPattern = """INR\s*([\d,]+\.?\d*)""",
            currencyPattern = null,
            datePattern = """settle before\s+(\d{2}-\w{3}-\d{4})""", // no longer matches
            dateFormat = "dd-MMM-yyyy",
            today = today,
        )
        assertNotNull(m)
        assertEquals(12480.50, m!!.amount!!, 0.001)
        assertNull(m.dueDate)
    }

    @Test fun `a pattern with no capture group falls back to the whole match`() {
        val m = ReminderExtractor.extractBill(
            text = statement, matchPattern = "statement",
            amountPattern = """[\d,]+\.\d{2}""", currencyPattern = "INR",
            datePattern = null, dateFormat = null, today = today,
        )
        assertEquals("12,480.50", m!!.amountRaw)
        assertEquals("INR", m.currency)
    }

    @Test fun `an invalid regex matches nothing instead of throwing`() {
        // A broken rule must never take down the scan of every other rule.
        assertNull(
            ReminderExtractor.extractBill(
                text = statement, matchPattern = "([unclosed",
                amountPattern = null, currencyPattern = null, datePattern = null, dateFormat = null,
            )
        )
        assertNull(ReminderExtractor.capture("([unclosed", statement))
        assertNotNull(ReminderExtractor.patternError("([unclosed"))
        assertNull(ReminderExtractor.patternError("""\d+"""))
        assertNull(ReminderExtractor.patternError(""))
    }

    @Test fun `matching ignores case and spans lines`() {
        val multiline = "Statement generated\nAmount: INR 500\nDue: 01/02/2026"
        assertTrue(ReminderExtractor.matches("statement GENERATED", multiline))
        assertEquals("500", ReminderExtractor.capture("""INR (\d+)""", multiline))
        // DOT_MATCHES_ALL: a pattern can reach across the newline between the lines.
        assertTrue(ReminderExtractor.matches("generated.*Due", multiline))
    }

    @Test fun `an empty capture counts as no capture`() {
        // An amount that matched empty is a failed extraction, not an amount of nothing.
        assertNull(ReminderExtractor.capture("""INR\s*(\d*)""", "INR "))
    }

    @Test fun `a parking ticket yields zone and expiry`() {
        val m = ReminderExtractor.extractParking(
            text = parkingTicket,
            matchPattern = "parking",
            zonePattern = """Zone:\s*([\w\- ]+?)\s*\.""",
            expiryPattern = """valid until\s+(\d{2}/\d{2}/\d{4} \d{2}:\d{2})""",
            dateFormat = "dd/MM/yyyy HH:mm",
            now = LocalDateTime.of(2026, 1, 15, 12, 0),
        )
        assertNotNull(m)
        assertEquals("ZONE-4B North Lot", m!!.zone)
        assertEquals(LocalDateTime.of(2026, 1, 15, 14, 30), m.expiry)
    }

    @Test fun `a parking match with an unreadable expiry still reports the match`() {
        // The caller needs to know the message was ours so it can ask for the missing detail -
        // returning null here would drop the session silently.
        val m = ReminderExtractor.extractParking(
            text = parkingTicket, matchPattern = "parking",
            zonePattern = """Zone:\s*([\w\- ]+?)\s*\.""",
            expiryPattern = """expires at\s+(\d{2}:\d{2})""", // doesn't match
            dateFormat = "HH:mm", now = LocalDateTime.of(2026, 1, 15, 12, 0),
        )
        assertNotNull(m)
        assertEquals("ZONE-4B North Lot", m!!.zone)
        assertNull(m.expiry)
    }

    @Test fun `notification text joins the parts a bank might use`() {
        val text = ReminderExtractor.notificationText("HDFC Bank", "Statement generated", "Due INR 500 by 07-Feb", null)
        assertTrue(text.contains("HDFC Bank"))
        assertTrue(text.contains("Statement generated"))
        assertTrue(text.contains("Due INR 500"))
    }

    @Test fun `notification text drops blanks and duplicates`() {
        // Android often repeats text into bigText; matching would otherwise see it twice.
        val text = ReminderExtractor.notificationText("Title", "Body", "Body", "")
        assertEquals("Title\nBody", text)
    }
}
