package com.noti.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.reminder.ReminderExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On the device's own (ICU) regex engine, where nested repetition really does backtrack. */
@RunWith(AndroidJUnit4::class)
class SlowPatternDeviceTest {

    // The lookahead forces the fallback engine; the nested group then backtracks exponentially.
    private val evil = "(?=a)(a+)+$"

    @Test(timeout = 10_000) fun a_runaway_fallback_pattern_is_stopped_and_remembered() {
        val t0 = System.currentTimeMillis()
        assertFalse(ReminderExtractor.matches(evil, "a".repeat(40) + "!"))
        assertTrue("stopped near the budget", System.currentTimeMillis() - t0 < 2_000)
        assertTrue(ReminderExtractor.isSlow(evil))
        // Skipped from then on, instantly.
        val t1 = System.currentTimeMillis()
        assertFalse(ReminderExtractor.matches(evil, "aaaa!"))
        assertTrue(System.currentTimeMillis() - t1 < 100)
    }

    @Test(timeout = 20_000) fun the_editor_refuses_a_runaway_fallback_pattern() {
        assertEquals(ReminderExtractor.SLOW_MESSAGE, ReminderExtractor.patternError("(?=x)(x+x+)+y"))
    }
}
