package com.noti.logger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.ui.ReminderRulesActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The rules list marks a rule with auto mark-paid on, on its existing sub-line - one line, and
 *  nothing added to a rule without it. Writes rules-list.png. */
@RunWith(AndroidJUnit4::class)
class RuleListAutoPaidTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db get() = NotiDatabase.get(ctx)

    @Before fun seed() {
        db.reminderRuleDao().deleteAll()
        db.reminderRuleDao().insert(ReminderRuleEntity(name = "ICICI card", type = "BILL", source = "SMS",
            sourceSender = "ICICIBANK-CARDS", payEnabled = 1, payMatchPattern = "Payment of"))
        db.reminderRuleDao().insert(ReminderRuleEntity(name = "Netflix", type = "BILL", source = "MANUAL"))
    }

    @After fun clear() = db.reminderRuleDao().deleteAll()

    private fun subs(root: android.view.View): Map<String, TextView> {
        val out = HashMap<String, TextView>()
        fun walk(v: android.view.View) {
            if (v is ViewGroup) {
                val title = v.findViewById<TextView>(R.id.row_title)
                val sub = v.findViewById<TextView>(R.id.row_sub)
                if (title != null && sub != null && title.parent == sub.parent) out[title.text.toString()] = sub
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    @Test fun auto_paid_shows_on_the_same_line() {
        ActivityScenario.launch(ReminderRulesActivity::class.java).use { sc ->
            Thread.sleep(1500)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(300)
            sc.onActivity { a ->
                val s = subs(a.window.decorView)
                // On every screen width: the flag stays, whole, on the one line (CI's narrow AVD
                // wrapped it to a second line before the description was made to give way).
                assertTrue(s["ICICI card"]!!.text.toString().endsWith("· Auto-paid"))
                val sub = s["ICICI card"]!!
                assertTrue("flag clipped", sub.layout.getLineWidth(0) <= sub.width - sub.totalPaddingLeft - sub.totalPaddingRight + 1)
                assertEquals(1, s["ICICI card"]!!.lineCount)
                assertTrue(!s["Netflix"]!!.text.contains("Auto-paid"))
                val root = a.window.decorView
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                File(a.getExternalFilesDir(null), "rules-list.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
