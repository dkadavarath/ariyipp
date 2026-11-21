package com.noti.logger.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowPatternTest {

    @Test(timeout = 5_000) fun `classic runaway patterns run in linear time`() {
        val t0 = System.currentTimeMillis()
        assertFalse(ReminderExtractor.matches("(a+)+$", "a".repeat(5_000) + "!"))
        assertNull(ReminderExtractor.capture("(x+x+)+y", "x".repeat(5_000)))
        assertFalse(ReminderExtractor.matches("(\\d*)*x", "1".repeat(5_000)))
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        // Linear patterns can't run away, so the editor has nothing to object to.
        assertNull(ReminderExtractor.patternError("(a+)+$"))
        assertTrue(ReminderExtractor.compile("(a+)+$") is ReminderExtractor.Compiled.Linear)
    }

    @Test fun `normal captures still work`() {
        assertEquals("18,402.75", ReminderExtractor.capture("""INR\s*([\d,]+\.?\d*)""", "Statement: INR 18,402.75 due"))
        assertEquals("07-Feb", ReminderExtractor.capture("""due by\s+(\d{2}-\w{3})""", "Payment due by 07-Feb."))
        assertTrue(ReminderExtractor.matches("statement", "Your STATEMENT is ready")) // case-insensitive
        assertTrue(ReminderExtractor.matches("ready.due", "ready\ndue"))            // dot matches newline
        assertEquals("whole", ReminderExtractor.capture("whole", "the whole match"))
    }

    @Test fun `lookarounds still work, on the fallback engine`() {
        assertTrue(ReminderExtractor.compile("(?<![A-Z])INR") is ReminderExtractor.Compiled.Backtracking)
        assertEquals("250", ReminderExtractor.capture("""(?<=INR )(\d+)""", "paid INR 250 today"))
    }

    @Test fun `a broken pattern is reported and matches nothing`() {
        assertTrue(ReminderExtractor.patternError("([") != null)
        assertFalse(ReminderExtractor.matches("([", "anything"))
    }
}
