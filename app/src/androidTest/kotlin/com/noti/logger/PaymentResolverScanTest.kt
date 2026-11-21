package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.reminder.BillStatus
import com.noti.logger.reminder.ReminderScanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The payment resolver through the real scan: statements and payment SMS land in the relayed chat,
 * a full rescan runs, and the right bills - and only those - end up paid.
 */
@RunWith(AndroidJUnit4::class)
class PaymentResolverScanTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db get() = NotiDatabase.get(ctx)
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()
    private var savedEnabled = false

    private val card = ReminderRuleEntity(
        name = "ICICI card", type = "BILL", source = "SMS", sourceSender = "ICICIB",
        matchPattern = "Statement", amountPattern = """Total of Rs\s*([\d,]+\.?\d*)""",
        datePattern = """due by\s*(\d{2}-\w{3}-\d{2})""", dateFormat = "dd-MMM-yy",
        payEnabled = 1, paySource = "SMS", paySender = "ICICIB",
        payMatchPattern = "Payment of", payAmountPattern = """Payment of Rs\s*([\d,]+\.?\d*)""",
    )

    private fun sms(at: Long, body: String, sender: String = "VM-ICICIB-S") =
        db.relayedMessageDao().insert(RelayedMessageEntity(sender = sender, sim = "", body = body, receivedAt = at))

    private fun statement(at: Long, amount: String, due: String) =
        sms(at, "ICICI Bank Credit Card XX4321 Statement is sent. Total of Rs $amount or minimum of Rs 100.00 is due by $due. Make your payment on time.")

    private fun payment(at: Long, amount: String) =
        sms(at, "Payment of Rs $amount has been received on your ICICI Bank Credit Card XX4321. Available limit is Rs 1,00,000.00.")

    private fun bills() = db.billOccurrenceDao().let { it.active() + it.history() }.sortedBy { it.dueAt }

    @Before fun setUp() {
        savedEnabled = Settings.get(ctx).reminderEnabled
        Settings.get(ctx).reminderEnabled = true
        wipe()
    }

    @After fun tearDown() {
        wipe()
        Settings.get(ctx).reminderEnabled = savedEnabled
        Thread.sleep(3000)
    }

    private fun wipe() {
        db.billOccurrenceDao().deleteAll()
        db.reminderRuleDao().deleteAll()
        db.relayedMessageDao().let { dao -> dao.conversations().forEach { dao.deleteConversation(it.sender) } }
    }

    @Test fun a_payment_after_the_statement_settles_it() {
        db.reminderRuleDao().insert(card)
        statement(now - 20 * day, "18,402.75", "06-OCT-26")
        payment(now - 10 * day, "18,402.75")
        ReminderScanner.scan(ctx, fullRescan = true)
        val b = bills().single()
        assertEquals(BillStatus.PAID, b.status)
        assertEquals(1, b.autoSettled)
    }

    @Test fun the_statement_mentioning_payment_is_not_its_own_payment() {
        db.reminderRuleDao().insert(card.copy(payMatchPattern = "payment"))
        statement(now - 20 * day, "18,402.75", "06-OCT-26") // says "Make your payment on time"
        ReminderScanner.scan(ctx, fullRescan = true)
        assertEquals(BillStatus.ACTIVE, bills().single().status)
    }

    @Test fun a_payment_never_settles_a_bill_generated_after_it_and_settles_only_one() {
        db.reminderRuleDao().insert(card)
        statement(now - 30 * day, "1,000.00", "06-OCT-26")
        payment(now - 25 * day, "1,000.00")
        statement(now - 5 * day, "2,000.00", "06-NOV-26")
        ReminderScanner.scan(ctx, fullRescan = true)
        ReminderScanner.scan(ctx, fullRescan = true) // the same payment, seen again
        val (oct, nov) = bills()
        assertEquals(BillStatus.PAID, oct.status)
        assertEquals(BillStatus.ACTIVE, nov.status)
    }

    @Test fun with_amount_matching_a_different_amount_settles_nothing() {
        db.reminderRuleDao().insert(card.copy(payMatchAmount = 1))
        statement(now - 20 * day, "18,402.75", "06-OCT-26")
        payment(now - 10 * day, "1,170.00") // paid the minimum, not the bill
        ReminderScanner.scan(ctx, fullRescan = true)
        assertEquals(BillStatus.ACTIVE, bills().single().status)

        payment(now - 5 * day, "18,402.75")
        ReminderScanner.scan(ctx, fullRescan = true)
        assertEquals(BillStatus.PAID, bills().single().status)
    }

    @Test fun a_manual_bill_is_settled_by_a_bank_debit_and_the_next_one_rolls() {
        val id = db.reminderRuleDao().insert(
            ReminderRuleEntity(
                name = "Rent", type = "BILL", source = "MANUAL", dueMode = "CONSTANT", recurrence = "MONTHLY",
                anchorDate = now + 10 * day, defaultAmount = 5500.0,
                payEnabled = 1, paySource = "SMS", paySender = "ENBD",
                payMatchPattern = "towards RENT", payAmountPattern = """AED\s*([\d,]+\.?\d*)\s+debited""",
                payMatchAmount = 1,
            )
        )
        ReminderScanner.rollManualRules(ctx)
        val first = db.billOccurrenceDao().activeForRule(id).single()
        sms(System.currentTimeMillis() + 1000, "AED 5,500.00 debited from a/c XX1234 towards RENT. Avl bal AED 12,000.00", sender = "ENBD")
        ReminderScanner.scan(ctx, fullRescan = true)
        assertEquals(BillStatus.PAID, db.billOccurrenceDao().byId(first.id)!!.status)
        // The next month's rent is generated once this one is settled - and stays open.
        val next = db.billOccurrenceDao().activeForRule(id).single()
        assertEquals(true, next.dueAt > first.dueAt)
    }

    /** Set up from a payment message that's already been scanned (the long-press path): saving the
     *  rule looks back, so the bill that payment covers is marked now - and only once. */
    @Test fun enabling_later_settles_a_payment_already_received() {
        val id = db.reminderRuleDao().insert(card.copy(payEnabled = 0))
        statement(now - 20 * day, "18,402.75", "06-OCT-26")
        payment(now - 10 * day, "18,402.75")
        ReminderScanner.scan(ctx, fullRescan = true)
        assertEquals(BillStatus.ACTIVE, bills().single().status) // resolver off: nothing settled

        db.reminderRuleDao().update(db.reminderRuleDao().byId(id)!!.copy(payEnabled = 1))
        assertEquals(1, ReminderScanner.resolvePaymentsFor(ctx, id))
        assertEquals(BillStatus.PAID, bills().single().status)
        assertEquals(0, ReminderScanner.resolvePaymentsFor(ctx, id)) // idempotent
    }
}
