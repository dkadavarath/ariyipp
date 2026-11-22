package com.noti.logger

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noti.logger.ui.ReminderRuleActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Long-press → "Create reminder rule" opens the editor with the rule already drafted: SMS source,
 * the sender reduced to its stable core, the message as the test sample, and the parser's patterns
 * reading the right total and due date in the live test. The sample field must not summon the
 * keyboard on its own. Writes rule-from-message.png for a visual check.
 */
@RunWith(AndroidJUnit4::class)
class RuleFromMessageTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sms = "ICICI Bank Credit Card XX4321 Statement is sent to user@example.com. Total of Rs 18,402.75 " +
        "or minimum of Rs 1,170.00 is due by 06-OCT-26."

    @Test fun the_rule_is_drafted_from_the_message() {
        val intent = Intent(ctx, ReminderRuleActivity::class.java)
            .putExtra(ReminderRuleActivity.EXTRA_SAMPLE, sms)
            .putExtra(ReminderRuleActivity.EXTRA_SENDER, "VM-ICICIB-S")
        ActivityScenario.launch<ReminderRuleActivity>(intent).use { scenario ->
            Thread.sleep(1500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { a ->
                fun t(id: Int) = a.findViewById<EditText>(id).text.toString()
                assertTrue(a.findViewById<RadioButton>(R.id.rb_source_sms).isChecked)
                assertEquals("ICICIB", t(R.id.et_sender))
                assertEquals(sms, t(R.id.et_sample))
                assertTrue("no amount pattern", t(R.id.et_amount).isNotBlank())
                assertTrue("no date pattern", t(R.id.et_date).isNotBlank())
                assertEquals("dd-MMM-yy", t(R.id.et_format))
                val result = a.findViewById<TextView>(R.id.tv_test_result).text.toString()
                assertTrue("live test: $result", result.contains("18402.75") && result.contains("2026-10-06"))
                assertFalse("sample field raises the keyboard", a.findViewById<EditText>(R.id.et_sample).showSoftInputOnFocus)

                val root = a.window.decorView
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                File(a.getExternalFilesDir(null), "rule-from-message.png").outputStream()
                    .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /** Long-press a payment SMS → "Use as payment for a rule" → that rule opens with its payment
     *  section on, the sender reduced, and the paid amount (not the limit after it) drafted. */
    @Test fun a_payment_message_drafts_the_payment_section() {
        val db = com.noti.logger.data.NotiDatabase.get(ctx)
        val id = db.reminderRuleDao().insert(
            com.noti.logger.data.ReminderRuleEntity(name = "ICICI card", type = "BILL", source = "SMS",
                sourceSender = "ICICIB", matchPattern = "Statement")
        )
        val paidSms = "Payment of Rs 18,402.75 has been received on your ICICI Bank Credit Card XX4321. Available limit is Rs 1,81,597.25."
        val intent = Intent(ctx, ReminderRuleActivity::class.java)
            .putExtra(ReminderRuleActivity.EXTRA_RULE_ID, id)
            .putExtra(ReminderRuleActivity.EXTRA_PAY_SAMPLE, paidSms)
            .putExtra(ReminderRuleActivity.EXTRA_PAY_SENDER, "AD-ICICIB")
        try {
            ActivityScenario.launch<ReminderRuleActivity>(intent).use { scenario ->
                Thread.sleep(2500)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { a ->
                    fun t(id: Int) = a.findViewById<EditText>(id).text.toString()
                    assertTrue(a.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.sw_pay_enabled).isChecked)
                    assertEquals("ICICIB", t(R.id.et_pay_sender))
                    assertTrue("no payment identify", t(R.id.et_pay_match).isNotBlank())
                    val result = a.findViewById<TextView>(R.id.tv_pay_test_result).text.toString()
                    assertTrue("pay test: $result", result.contains("18402.75"))
                    // The bill's own fields are untouched.
                    assertEquals("Statement", t(R.id.et_match))

                    val root = a.window.decorView
                    val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bmp))
                    File(a.getExternalFilesDir(null), "rule-payment.png").outputStream()
                        .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally {
            db.reminderRuleDao().delete(id)
        }
    }
}
