package com.noti.logger

import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.reminder.PatternBuilder
import com.noti.logger.ui.ReminderRuleActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "select a value, get a pattern" path, end to end through the real editor: build, verify,
 * fill the field, re-run the test. [PatternBuilderTest] covers the pattern logic itself; this
 * covers the wiring, which is where a field id or a date-format hookup goes wrong silently.
 */
@RunWith(AndroidJUnit4::class)
class PatternFromSelectionTest {

    private val statement =
        "Dear Customer, your HDFC Bank Credit Card statement for Jan 2026 is generated. " +
            "Total Amount Due INR 12,480.50. Payment due by 07-Feb-2026."

    private fun select(text: String) = statement.indexOf(text).let { it to it + text.length }

    @Test fun selecting_the_amount_fills_the_amount_field_and_the_test_result() {
        ActivityScenario.launch(ReminderRuleActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<EditText>(R.id.et_sample).setText(statement)
                val (s, e) = select("12,480.50")
                assertTrue(a.applySelection(statement, s, e, PatternBuilder.Field.AMOUNT, R.id.et_amount))

                val pattern = a.findViewById<EditText>(R.id.et_amount).text.toString()
                assertTrue("field should hold a pattern, was '$pattern'", pattern.isNotBlank())
                // It must be a pattern, not the literal value pasted back in.
                assertFalse("value must not be baked in", pattern == "12,480.50")
            }
            // And the live test should now report the extraction (it runs off the main thread).
            var result = ""
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && !result.contains("12480.5")) {
                scenario.onActivity { a -> result = a.findViewById<TextView>(R.id.tv_test_result).text.toString() }
                if (!result.contains("12480.5")) Thread.sleep(50)
            }
            assertTrue("test result was '$result'", result.contains("12480.5"))
        }
    }

    @Test fun selecting_a_date_also_fills_the_date_format() {
        // The format field is the one people forget, and a rule that matches but produces no date
        // is the usual result. Selecting the date has to set both.
        ActivityScenario.launch(ReminderRuleActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<EditText>(R.id.et_sample).setText(statement)
                val (s, e) = select("07-Feb-2026")
                assertTrue(a.applySelection(statement, s, e, PatternBuilder.Field.DATE, R.id.et_date))

                assertTrue(a.findViewById<EditText>(R.id.et_date).text.toString().isNotBlank())
                assertEquals("dd-MMM-yyyy", a.findViewById<EditText>(R.id.et_format).text.toString())
            }
        }
    }

    @Test fun a_selection_it_cannot_use_leaves_the_field_untouched() {
        ActivityScenario.launch(ReminderRuleActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<EditText>(R.id.et_sample).setText(statement)
                a.findViewById<EditText>(R.id.et_amount).setText("keep me")
                val (s, e) = select("Dear Customer")
                assertFalse(a.applySelection(statement, s, e, PatternBuilder.Field.AMOUNT, R.id.et_amount))
                assertEquals("keep me", a.findViewById<EditText>(R.id.et_amount).text.toString())
            }
        }
    }
}
