package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.reminder.BillStatus
import com.noti.logger.reminder.DueDates
import com.noti.logger.reminder.ParkingTracker
import com.noti.logger.reminder.ReminderPolicy
import com.noti.logger.reminder.ReminderScanner
import com.noti.logger.reminder.ReminderScheduler
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reminder engine end to end on the device database: the scanner's bill and payment paths,
 * the parking tracker's start / extend / end, the scheduler's arming, and the queries under them.
 * These are the paths most reminder bugs (R1, R2, R6, the "skip next" burst) lived in.
 */
@RunWith(AndroidJUnit4::class)
class ReminderEngineTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db get() = NotiDatabase.get(ctx)
    private val sender = "TESTBK"
    private var saved = Triple(false, 9, 5)

    private fun card(name: String = "Card", skipNext: Int = 0, match: String = "Statement") = ReminderRuleEntity(
        name = name, type = "BILL", source = "SMS", sourceSender = sender,
        matchPattern = match, amountPattern = """Rs\s*([\d,]+\.?\d*)""",
        datePattern = """due by\s+(\d{1,2}-\w{3}-\d{4})""", dateFormat = "d-MMM-yyyy",
        skipNext = skipNext,
        payEnabled = 1, paySource = "SMS", paySender = sender, payMatchPattern = "payment of",
        payAmountPattern = """payment of Rs\s*([\d,]+\.?\d*)""",
    )

    private var t = System.currentTimeMillis() - 60 * 60_000L
    private fun sms(body: String) {
        t += 60_000
        db.relayedMessageDao().insert(RelayedMessageEntity(sender = sender, sim = "", body = body, receivedAt = t))
    }

    private fun bills(): List<BillOccurrenceEntity> = db.billOccurrenceDao().let { it.active() + it.history() }
    private fun rescan() = ReminderScanner.scan(ctx, fullRescan = true)

    @Before fun setUp() {
        val s = Settings.get(ctx)
        saved = Triple(s.reminderEnabled, s.reminderHour, s.reminderSpacingMinutes)
        s.reminderEnabled = true
        wipe()
    }

    @After fun tearDown() {
        wipe()
        val s = Settings.get(ctx)
        s.reminderEnabled = saved.first; s.reminderHour = saved.second; s.reminderSpacingMinutes = saved.third
    }

    private fun wipe() {
        db.billOccurrenceDao().deleteAll()
        db.reminderRuleDao().deleteAll()
        com.noti.logger.reminder.ReminderTrigger.invalidateWatched()
        db.parkingSessionDao().deleteAll()
        db.relayedMessageDao().deleteConversation(sender)
    }

    // ---- Scanner: bills ----

    @Test fun a_resent_statement_updates_the_bill_instead_of_adding_one() {
        db.reminderRuleDao().insert(card())
        sms("Statement: total Rs 1,200.00 due by 5-Mar-2027")
        sms("Reminder - Statement: total Rs 1,200.00 due by 5-Mar-2027")
        rescan()
        assertEquals(1, bills().size)
    }

    @Test fun two_cards_due_the_same_day_stay_two_bills() {
        db.reminderRuleDao().insert(card())
        sms("Statement card 1234: total Rs 1,200.00 due by 5-Mar-2027")
        sms("Statement card 9876: total Rs 340.50 due by 5-Mar-2027")
        rescan()
        assertEquals(setOf(1200.0, 340.5), bills().mapNotNull { it.amount }.toSet())
    }

    @Test fun a_reminder_leading_with_the_minimum_due_is_the_same_bill() {
        db.reminderRuleDao().insert(card())
        sms("Statement: total Rs 18,402.75 due by 5-Mar-2027")
        sms("Statement reminder: Rs 920.00 min due of total 18,402.75 due by 5-Mar-2027")
        rescan()
        val bill = bills().single()
        assertEquals("the amount read first stays", 18402.75, bill.amount!!, 0.001)
    }

    @Test fun a_rescan_keeps_what_you_corrected_and_fills_the_rest() {
        db.reminderRuleDao().insert(card())
        sms("Statement: total Rs 1,200.00 due by 5-Mar-2027")
        rescan()
        val bill = bills().single()
        val mine = DueDates.toMs(LocalDate.of(2027, 3, 9))
        db.billOccurrenceDao().update(bill.copy(dueAt = mine, editedFields = BillOccurrenceEntity.EDITED_DUE))
        rescan()
        val after = bills().single()
        assertEquals("my date kept", mine, after.dueAt)
        assertEquals("amount still from the statement", 1200.0, after.amount!!, 0.0)
    }

    @Test fun skip_next_skips_exactly_one_bill_of_a_burst() {
        db.reminderRuleDao().insert(card(skipNext = 1))
        sms("Statement: total Rs 100 due by 5-Mar-2027")
        sms("Statement: total Rs 200 due by 5-Apr-2027")
        sms("Statement: total Rs 300 due by 5-May-2027")
        rescan()
        assertEquals(3, bills().size)
        assertEquals(1, bills().count { it.status == BillStatus.SKIPPED })
        assertEquals(0, db.reminderRuleDao().all().single().skipNext)
    }

    // ---- Scanner: payments ----

    @Test fun a_payment_settles_its_bill_and_a_statement_never_settles_itself() {
        db.reminderRuleDao().insert(card())
        // "payment of" appears in the statement itself: it must not count as the payment.
        sms("Statement: total Rs 1,200.00 due by 5-Mar-2027. Make a payment of Rs 1,200.00 by then")
        rescan()
        assertEquals(BillStatus.ACTIVE, bills().single().status)
        sms("We received your payment of Rs 1,200.00. Thank you")
        rescan()
        val bill = bills().single()
        assertEquals(BillStatus.PAID, bill.status)
        assertEquals(1, bill.autoSettled)
    }

    // ---- Parking tracker ----

    private val parkingRule = ReminderRuleEntity(name = "Parking", type = "PARKING", source = "SMS", sourceSender = "PARK")

    @Test fun parking_starts_extends_and_ends_and_old_tickets_stay_history() {
        val ruleId = db.reminderRuleDao().insert(parkingRule)
        val rule = db.reminderRuleDao().byId(ruleId)!!
        val now = LocalDateTime.now()
        // An expired ticket - a rescan of last month - opens nothing.
        assertFalse(ParkingTracker.onMatch(ctx, rule, "A1", now.minusHours(2), System.currentTimeMillis() - 3 * 3_600_000L))
        assertEquals(0, db.parkingSessionDao().active().size)
        // A live one starts a session; a renewal for the same zone extends it.
        assertTrue(ParkingTracker.onMatch(ctx, rule, "A1", now.plusHours(1)))
        assertTrue(ParkingTracker.onMatch(ctx, rule, "A1", now.plusHours(2)))
        val session = db.parkingSessionDao().active().single()
        assertEquals(1, session.renewals)
        assertEquals(ReminderPolicy.toEpochMillis(now.plusHours(2)), session.expiresAt)
        // The same ticket again is not a renewal.
        assertFalse(ParkingTracker.onMatch(ctx, rule, "A1", now.plusHours(2)))
        ParkingTracker.end(ctx, session.id)
        assertEquals(0, db.parkingSessionDao().active().size)
    }

    @Test fun a_late_parking_reminder_is_armed_once() {
        val ruleId = db.reminderRuleDao().insert(parkingRule)
        val rule = db.reminderRuleDao().byId(ruleId)!!
        // Expiry 5 minutes out with the default 10-minute lead: the reminder time is already past.
        ParkingTracker.onMatch(ctx, rule, "B2", LocalDateTime.now().plusMinutes(5))
        val s = db.parkingSessionDao().active().single()
        assertEquals("rang (armed late) for this reminder time", s.remindAt, s.rangFor)
        // A rebuild doesn't arm it again.
        ReminderScheduler.rebuildAll(ctx)
        assertEquals(s.remindAt, db.parkingSessionDao().byId(s.id)!!.rangFor)
    }

    @Test fun an_ongoing_countdown_is_kept_only_when_what_the_rule_reads_changes() {
        db.reminderRuleDao().insert(ReminderRuleEntity(
            name = "Park app", type = "PARKING", source = "NOTIFICATION", sourcePackage = "com.example.park",
            matchPattern = "Parking", zonePattern = """Zone (\w+)""", expiryPattern = """until (\d{1,2}:\d{2})""",
            dateFormat = "H:mm",
        ))
        com.noti.logger.reminder.ReminderTrigger.invalidateWatched()
        val sig = { text: String -> com.noti.logger.reminder.ReminderTrigger.ongoingSignature(ctx, "com.example.park", text) }
        val first = sig("Parking Zone A1 until 14:30 - 23 min left")
        assertTrue(first != null)
        assertEquals("a countdown tick is the same", first, sig("Parking Zone A1 until 14:30 - 22 min left"))
        assertTrue("a renewal is new", first != sig("Parking Zone A1 until 15:30 - 82 min left"))
        assertEquals("not the rule's at all", null, sig("Now playing: a song"))
        assertEquals("another app", null, com.noti.logger.reminder.ReminderTrigger.ongoingSignature(ctx, "com.other", "Parking Zone A1 until 14:30"))
    }

    // ---- Scheduler ----

    @Test fun rebuild_arms_each_bill_on_its_own_day_in_time_order() {
        val s = Settings.get(ctx)
        s.reminderHour = 9; s.reminderSpacingMinutes = 5
        val dao = db.billOccurrenceDao()
        val d1 = LocalDate.now().plusDays(20)
        val d2 = LocalDate.now().plusDays(21)
        // Listed by due date, but the later bill warns earlier (7 days vs 1).
        dao.insert(BillOccurrenceEntity(name = "soon", dueAt = DueDates.toMs(d1), remindDaysBefore = 1, sourceKey = "e1"))
        dao.insert(BillOccurrenceEntity(name = "later", dueAt = DueDates.toMs(d2), remindDaysBefore = 7, sourceKey = "e2"))
        ReminderScheduler.rebuildAll(ctx)
        val byName = dao.active().associateBy { it.name }
        assertEquals(LocalDateTime.of(d1.minusDays(1), java.time.LocalTime.of(9, 0)), ReminderPolicy.fromEpochMillis(byName.getValue("soon").remindAt))
        assertEquals(LocalDateTime.of(d2.minusDays(7), java.time.LocalTime.of(9, 0)), ReminderPolicy.fromEpochMillis(byName.getValue("later").remindAt))
    }

    // ---- Queries ----

    @Test fun claim_skip_next_is_given_out_once() {
        val id = db.reminderRuleDao().insert(card(skipNext = 1))
        assertEquals(1, db.reminderRuleDao().claimSkipNext(id))
        assertEquals(0, db.reminderRuleDao().claimSkipNext(id))
    }

    @Test fun bills_for_a_rule_and_due_date() {
        val ruleId = db.reminderRuleDao().insert(card())
        val due = DueDates.toMs(LocalDate.of(2027, 3, 5))
        db.billOccurrenceDao().insert(BillOccurrenceEntity(ruleId = ruleId, name = "a", dueAt = due, sourceKey = "q1"))
        db.billOccurrenceDao().insert(BillOccurrenceEntity(ruleId = ruleId, name = "b", dueAt = due, sourceKey = "q2"))
        db.billOccurrenceDao().insert(BillOccurrenceEntity(ruleId = ruleId, name = "c", dueAt = due + DueDates.DAY_MS, sourceKey = "q3"))
        assertEquals(listOf("a", "b"), db.billOccurrenceDao().forRuleAndDue(ruleId, due).map { it.name })
    }
}
