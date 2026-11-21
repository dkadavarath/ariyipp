package com.noti.logger.reminder

import com.noti.logger.data.BillOccurrenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentResolverTest {

    private val day = 86_400_000L
    private fun bill(id: Long, issued: Long, amount: Double? = null, status: Int = BillStatus.ACTIVE) =
        BillOccurrenceEntity(id = id, ruleId = 1, name = "card", amount = amount, status = status,
            issuedAt = issued, createdAt = issued + 5_000, sourceKey = "k$id")

    @Test fun `settles the newest bill issued before the payment`() {
        val bills = listOf(bill(1, 10 * day), bill(2, 40 * day))
        assertEquals(2L, PaymentResolver.pick(bills, 45 * day, null, matchAmount = false)!!.id)
    }

    @Test fun `never settles a bill generated after the payment`() {
        val bills = listOf(bill(1, 10 * day), bill(2, 40 * day))
        assertEquals(1L, PaymentResolver.pick(bills, 20 * day, null, matchAmount = false)!!.id)
        assertNull(PaymentResolver.pick(listOf(bill(3, 50 * day)), 20 * day, null, matchAmount = false))
    }

    @Test fun `amount matching picks the bill with that amount`() {
        val bills = listOf(bill(1, 10 * day, 1200.0), bill(2, 40 * day, 18402.75))
        assertEquals(1L, PaymentResolver.pick(bills, 45 * day, 1200.0, matchAmount = true)!!.id)
        // Within a unit of rounding.
        assertEquals(2L, PaymentResolver.pick(bills, 45 * day, 18403.0, matchAmount = true)!!.id)
    }

    @Test fun `amount matching with no fitting amount settles nothing`() {
        val bills = listOf(bill(1, 10 * day, 1200.0))
        assertNull(PaymentResolver.pick(bills, 45 * day, 500.0, matchAmount = true))
        assertNull(PaymentResolver.pick(bills, 45 * day, null, matchAmount = true))
    }

    @Test fun `settled bills are not candidates`() {
        assertNull(PaymentResolver.pick(listOf(bill(1, day, status = BillStatus.PAID)), 2 * day, null, false))
    }

    @Test fun `pre-v13 rows fall back to createdAt`() {
        val old = BillOccurrenceEntity(id = 9, ruleId = 1, name = "x", createdAt = 10 * day, sourceKey = "o")
        assertEquals(9L, PaymentResolver.pick(listOf(old), 11 * day, null, false)!!.id)
        assertNull(PaymentResolver.pick(listOf(old), 9 * day, null, false))
    }

    // ---- Drafting the payment side from a confirmation message ----

    private fun paid(sample: String): Double? {
        val d = RuleAutoParser.draftPayment(sample)
        assertEquals("identify doesn't match its sample", true, ReminderExtractor.matches(d.matchPattern, sample))
        return ReminderPolicy.parseAmount(ReminderExtractor.capture(d.amountPattern, sample))
    }

    @Test fun `payment draft takes the paid amount, not the balance after it`() {
        assertEquals(18402.75, paid("Payment of Rs 18,402.75 has been received on your ICICI Bank Credit Card XX4321. " +
            "Available limit is Rs 1,81,597.25.")!!, 0.001)
        assertEquals(412.5, paid("Thank you for your payment of AED 412.50 towards DEWA account 2001234. " +
            "Outstanding balance AED 0.00")!!, 0.001)
    }

    @Test fun `bank debit for a manual bill`() {
        assertEquals(5500.0, paid("AED 5,500.00 debited from a/c XX1234 towards RENT on 01-Nov-26. Avl bal AED 12,000.00")!!, 0.001)
    }

    @Test fun `drafted amount patterns are anchored, not "the first number"`() {
        val d = RuleAutoParser.draftPayment("Payment of Rs 18,402.75 has been received on your card XX4321.")
        assertEquals(true, d.amountPattern!!.startsWith("Rs"))
        // Still reads next month's message with a reference number added in front.
        assertEquals(999.0, ReminderPolicy.parseAmount(ReminderExtractor.capture(d.amountPattern,
            "Ref 88231: Payment of Rs 999.00 has been received on your card XX4321."))!!, 0.001)
    }
}
