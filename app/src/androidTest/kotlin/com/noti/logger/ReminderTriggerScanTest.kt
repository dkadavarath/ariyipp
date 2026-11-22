package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.NotificationEntity
import com.noti.logger.data.RelayedMessageEntity
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.reminder.ReminderScanner
import com.noti.logger.reminder.ReminderTrigger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The rulebook runs on arrival: an eligible message becomes a bill without waiting for the schedule. */
@RunWith(AndroidJUnit4::class)
class ReminderTriggerScanTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db get() = NotiDatabase.get(ctx)
    private val now = System.currentTimeMillis()
    private var savedEnabled = false
    private val pkg = "test.trigger.$now"

    private val card = ReminderRuleEntity(
        name = "ICICI card", type = "BILL", source = "SMS", sourceSender = "ICICIB",
        matchPattern = "Statement", amountPattern = """Total of Rs\s*([\d,]+\.?\d*)""",
    )
    private val app = ReminderRuleEntity(
        name = "Power", type = "BILL", source = "NOTIFICATION", sourcePackage = pkg,
        matchPattern = "bill", amountPattern = """AED\s*([\d,]+\.?\d*)""",
    )

    private fun bills() = db.billOccurrenceDao().let { it.active() + it.history() }

    @Before fun setUp() {
        savedEnabled = Settings.get(ctx).reminderEnabled
        Settings.get(ctx).reminderEnabled = true
        wipe()
    }

    @After fun tearDown() {
        wipe()
        Settings.get(ctx).reminderEnabled = savedEnabled
    }

    private fun wipe() {
        db.billOccurrenceDao().deleteAll()
        db.reminderRuleDao().deleteAll()
        db.relayedMessageDao().let { dao -> dao.conversations().forEach { dao.deleteConversation(it.sender) } }
    }

    private fun notif(postTime: Long, text: String) = runBlocking {
        db.notificationDao().insert(
            NotificationEntity(
                uid = "t|$postTime|$text", packageName = pkg, appLabel = "Power", postTime = postTime,
                title = "Power", text = text, bigText = null, subText = null, category = null,
                sbnKey = null, createdAt = System.currentTimeMillis(),
            )
        )
    }

    private fun waitFor(expected: Int): Int {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            if (bills().size >= expected) break
            Thread.sleep(250)
        }
        Thread.sleep(500)
        return bills().size
    }

    @Test fun an_eligible_sms_becomes_a_bill_on_arrival() {
        db.reminderRuleDao().insert(card)
        ReminderScanner.scan(ctx) // brings the watermarks up to now, as the schedule would have
        db.relayedMessageDao().insert(RelayedMessageEntity(sender = "VM-ICICIB-S", sim = "", receivedAt = now,
            body = "ICICI Bank Credit Card XX4321 Statement is sent. Total of Rs 1,234.00 is due by 06-DEC-26."))
        ReminderTrigger.onSms(ctx, "VM-ICICIB-S")
        assertEquals(1, waitFor(1))
        assertEquals(1234.0, bills().single().amount!!, 0.001)
    }

    @Test fun an_eligible_notification_becomes_a_bill_on_arrival() {
        db.reminderRuleDao().insert(app)
        ReminderScanner.scan(ctx)
        notif(now, "Your bill of AED 412.50 is ready")
        ReminderTrigger.onNotification(ctx, pkg)
        assertEquals(1, waitFor(1))
    }

    @Test fun a_notification_posted_before_the_last_scan_but_stored_after_it_is_not_lost() {
        db.reminderRuleDao().insert(app)
        ReminderScanner.scan(ctx)
        // Posted a minute ago, written only now - behind any time watermark the scan just set.
        notif(System.currentTimeMillis() - 60_000, "Your bill of AED 99.00 is ready")
        ReminderScanner.scan(ctx)
        assertEquals(1, bills().size)
    }

    @Test fun an_unrelated_sms_starts_nothing() {
        db.reminderRuleDao().insert(card)
        ReminderScanner.scan(ctx)
        db.relayedMessageDao().insert(RelayedMessageEntity(sender = "Mom", sim = "", receivedAt = now,
            body = "Statement: Total of Rs 5.00, love mom"))
        ReminderTrigger.onSms(ctx, "Mom")
        Thread.sleep(3000)
        assertEquals(0, bills().size)
    }
}
