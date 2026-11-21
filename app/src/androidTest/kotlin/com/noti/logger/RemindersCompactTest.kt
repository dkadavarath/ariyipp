package com.noti.logger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.noti.logger.config.Settings
import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.reminder.ReminderScanner
import com.noti.logger.ui.MainActivity
import com.noti.shared.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Reminders tab in both layouts, against the real fragment. Compact must hold every entry to
 * one line - name, due, amount and its action icons - however long the name; the full layout is
 * rendered too so the two can be compared. Each run also writes PNGs (software-drawn, so they work
 * on an emulator whose screencap is black) to the app's external files dir for a visual check.
 *
 * Also covers the repeating-manual-bill amount: the rule now carries it, so the occurrence it
 * generates is created with it rather than blank.
 */
@RunWith(AndroidJUnit4::class)
class RemindersCompactTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db get() = NotiDatabase.get(ctx)
    private var savedRole: Role? = null
    private var savedEnabled = false
    private var savedCompact = false

    private fun midnight(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun seed() {
        val s = Settings.get(ctx)
        savedRole = s.role; savedEnabled = s.reminderEnabled; savedCompact = s.remindersCompact
        s.role = Role.MAIN
        s.reminderEnabled = true
        db.billOccurrenceDao().deleteAll()
        db.reminderRuleDao().deleteAll()
        val today = LocalDate.now()
        val dao = db.billOccurrenceDao()
        dao.insert(BillOccurrenceEntity(ruleId = 0, name = "ICICI Credit Card XX4321 statement for the month", dueAt = midnight(today.plusDays(5)),
            amount = 18402.75, currency = "Rs", sourceKey = "t1"))
        dao.insert(BillOccurrenceEntity(ruleId = 0, name = "DEWA", dueAt = midnight(today.minusDays(3)), amount = 412.0, currency = "AED", sourceKey = "t2"))
        dao.insert(BillOccurrenceEntity(ruleId = 0, name = "Netflix", dueAt = midnight(today.plusDays(1)), sourceKey = "t3"))
        // A repeating manual bill, made the way the Add screen makes one: a MANUAL rule with an amount.
        db.reminderRuleDao().insert(
            ReminderRuleEntity(name = "Gym", type = "BILL", source = "MANUAL", dueMode = "CONSTANT",
                recurrence = "MONTHLY", anchorDate = midnight(today.plusDays(10)), defaultAmount = 299.0, defaultCurrency = "AED")
        )
        ReminderScanner.rollManualRules(ctx)
    }

    @After fun restore() {
        db.billOccurrenceDao().deleteAll()
        db.reminderRuleDao().deleteAll()
        val s = Settings.get(ctx)
        s.role = savedRole; s.reminderEnabled = savedEnabled; s.remindersCompact = savedCompact
        Thread.sleep(3000) // encrypted prefs apply() is async; let it land before the next test
    }

    @Test fun a_repeating_manual_bill_is_created_with_its_amount() {
        val gym = db.billOccurrenceDao().active().single { it.name == "Gym" }
        assertEquals(299.0, gym.amount!!, 0.0)
        assertEquals("AED", gym.currency)
    }

    @Test fun compact_rows_are_one_line_with_inline_actions() {
        val rows = render(compact = true, png = "reminders-compact.png")
        val density = ctx.resources.displayMetrics.density
        assertEquals(4, rows.size)
        for (row in rows) {
            val name = row.findViewById<TextView>(R.id.tv_row_name)
            assertEquals("name wrapped: ${name.text}", 1, name.lineCount)
            assertTrue("row too tall: ${row.height / density}dp", row.height <= 56 * density)
            val paid = row.findViewById<MaterialButton>(R.id.btn_row_primary)
            assertEquals(View.VISIBLE, paid.visibility)
            assertEquals("Mark paid", paid.contentDescription)
            // The amount sits on the same line as the name.
            val amount = row.findViewById<TextView>(R.id.tv_row_amount)
            assertTrue(kotlin.math.abs(amount.top - name.top) < 8 * density)
        }
        val gym = rows.single { it.findViewById<TextView>(R.id.tv_row_name).text == "Gym" }
        assertEquals(View.VISIBLE, gym.findViewById<View>(R.id.btn_row_secondary).visibility) // skip next
        assertTrue(gym.findViewById<TextView>(R.id.tv_row_amount).text.contains("299"))
    }

    /** The total is one AED figure for the next 30 days; the bill without an amount is called out
     *  rather than silently left out. (The INR bill converts if the emulator can reach the rates
     *  service; either way it is in the figure or named in the note, never dropped.) */
    @Test fun the_total_is_one_aed_figure() {
        render(compact = false, png = "reminders-total-full.png")
        val card = lastTotal!!
        assertTrue("total: ${card.first}", card.first.startsWith("AED "))
        assertTrue("notes: ${card.second}", card.second.contains("4 bills") && card.second.contains("1 without an amount"))
        assertTrue("INR neither converted nor named: ${card.second}",
            card.second.contains("rates of") || card.second.contains("Rs"))
    }

    @Test fun in_compact_mode_the_total_is_one_line() {
        render(compact = true, png = "reminders-total.png")
        assertEquals(View.GONE, lastTitleVisibility)
        assertEquals(View.GONE, lastSubVisibility)
        assertEquals(1, lastTotalLines)
        assertTrue("compact total: ${lastTotal!!.first}", lastTotal!!.first.contains("AED ") && lastTotal!!.first.contains("4 bills"))
    }

    @Test fun the_total_can_be_hidden() {
        Settings.get(ctx).remindersShowTotal = false
        render(compact = true, png = "reminders-no-total.png")
        assertEquals(View.GONE, lastTotalVisibility)
        Settings.get(ctx).remindersShowTotal = true
    }

    @Test fun a_rule_bill_offers_edit_rule_and_a_one_off_does_not() {
        val bills = db.billOccurrenceDao().active()
        fun buttonFor(name: String): Int {
            val id = bills.single { it.name == name }.id
            var vis = -1
            ActivityScenario.launch<com.noti.logger.ui.ReminderEditActivity>(
                android.content.Intent(ctx, com.noti.logger.ui.ReminderEditActivity::class.java)
                    .putExtra(com.noti.logger.ui.ReminderEditActivity.EXTRA_BILL_ID, id)
            ).use { sc ->
                Thread.sleep(1500)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                sc.onActivity { vis = it.findViewById<View>(R.id.btn_edit_rule).visibility }
            }
            return vis
        }
        assertEquals(View.VISIBLE, buttonFor("Gym"))  // from a manual repeating rule
        assertEquals(View.GONE, buttonFor("DEWA"))    // one-off
    }

    /** Marking the top bill paid moves its row to the end (its rule's greyed "last paid" row); the
     *  list used to scroll down after it. The screen must stay at the top. Full layout, so the list
     *  is longer than the screen - the only case where it could scroll at all. */
    @Test fun marking_the_top_bill_paid_keeps_the_list_at_the_top() {
        val ruleId = db.reminderRuleDao().insert(ReminderRuleEntity(name = "Card", type = "BILL", source = "SMS"))
        db.billOccurrenceDao().insert(BillOccurrenceEntity(ruleId = ruleId, name = "Card", amount = 50.0,
            dueAt = midnight(LocalDate.now().minusDays(9)), sourceKey = "top"))
        Settings.get(ctx).remindersCompact = false
        Thread.sleep(1500)
        val intent = android.content.Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_REMINDERS)
        ActivityScenario.launch<MainActivity>(intent).use { sc ->
            Thread.sleep(3000)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var firstName = ""
            sc.onActivity { a ->
                val rv = a.findViewById<RecyclerView>(R.id.rv_reminders)
                val top = rv.getChildAt(0)
                firstName = top.findViewById<TextView>(R.id.tv_row_name).text.toString()
                top.findViewById<MaterialButton>(R.id.btn_row_primary).performClick()
            }
            assertEquals("Card", firstName)
            Thread.sleep(2500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            sc.onActivity { a ->
                val rv = a.findViewById<RecyclerView>(R.id.rv_reminders)
                assertEquals("list scrolled away from the top", 0, rv.computeVerticalScrollOffset())
                val lm = rv.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                assertEquals(0, lm.findFirstVisibleItemPosition())
                assertTrue(rv.getChildAt(0).findViewById<TextView>(R.id.tv_row_name).text.toString() != "Card")
            }
        }
    }

    private var lastTotal: Pair<String, String>? = null
    private var lastTotalVisibility = -1
    private var lastTitleVisibility = -1
    private var lastSubVisibility = -1
    private var lastTotalLines = -1

    @Test fun full_layout_still_renders() {
        val rows = render(compact = false, png = "reminders-full.png")
        // Full cards are tall: only the first few fit on screen, which is the point of compact.
        assertTrue(rows.isNotEmpty())
        assertEquals("Mark paid", rows[0].findViewById<MaterialButton>(R.id.btn_row_primary).text.toString())
    }

    private fun render(compact: Boolean, png: String): List<View> {
        Settings.get(ctx).remindersCompact = compact
        Thread.sleep(1500)
        val out = mutableListOf<View>()
        // Opened straight on the tab, as a reminder notification does: a tab *switch* animates, and
        // the fade-through never completes on a headless emulator, leaving the fragment un-resumed.
        val intent = android.content.Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_REMINDERS)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // The list loads off the main thread and rows animate in; wait for them to settle.
            // ...and for the total, which may wait on the (daily) exchange-rate fetch.
            val wantTotal = Settings.get(ctx).remindersShowTotal
            var count = 0
            var totalShown = false
            for (i in 0 until 80) {
                Thread.sleep(250)
                scenario.onActivity {
                    count = it.findViewById<RecyclerView>(R.id.rv_reminders).childCount
                    totalShown = it.findViewById<View>(R.id.card_totals).visibility == View.VISIBLE
                }
                if (count >= 3 && (totalShown || !wantTotal)) break
            }
            Thread.sleep(800)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val rv = activity.findViewById<RecyclerView>(R.id.rv_reminders)
                for (i in 0 until rv.childCount) out += rv.getChildAt(i)
                lastTotalVisibility = activity.findViewById<View>(R.id.card_totals).visibility
                lastTitleVisibility = activity.findViewById<View>(R.id.tv_totals_title).visibility
                lastSubVisibility = activity.findViewById<View>(R.id.tv_upcoming).visibility
                lastTotalLines = activity.findViewById<TextView>(R.id.tv_totals).lineCount
                lastTotal = activity.findViewById<TextView>(R.id.tv_totals).text.toString() to
                    activity.findViewById<TextView>(R.id.tv_upcoming).text.toString()
                val root = activity.window.decorView
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                File(activity.getExternalFilesDir(null), png).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        return out
    }
}
