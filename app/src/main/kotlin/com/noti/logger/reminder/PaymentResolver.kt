package com.noti.logger.reminder

import com.noti.logger.data.BillOccurrenceEntity
import kotlin.math.abs

/**
 * Decides which outstanding bill a payment confirmation settles. Pure, so the rules are testable.
 *
 * A payment can only settle a bill that already existed when it was made: a bill issued after the
 * payment is next cycle's, and settling it would hide the very bill still to be paid. Among the
 * eligible ones the newest wins - the statement you are paying is the latest one, and an older bill
 * still open was missed rather than being paid now.
 *
 * With amount matching on, the paid amount must also equal the bill's within [AMOUNT_TOLERANCE]
 * (rounding, or a bank that prints the payment without its paise). A payment whose amount can't be
 * read settles nothing in that mode - a guess is exactly what matching was turned on to prevent.
 */
object PaymentResolver {

    const val AMOUNT_TOLERANCE = 1.0

    /** When a bill counts as issued; rows from before v13 have only [BillOccurrenceEntity.createdAt]. */
    fun issuedAt(bill: BillOccurrenceEntity): Long = if (bill.issuedAt > 0) bill.issuedAt else bill.createdAt

    fun pick(
        outstanding: List<BillOccurrenceEntity>,
        paidAtMs: Long,
        paidAmount: Double?,
        matchAmount: Boolean,
    ): BillOccurrenceEntity? {
        val eligible = outstanding.filter { it.status == BillStatus.ACTIVE && issuedAt(it) <= paidAtMs }
        val pool = if (!matchAmount) eligible else {
            if (paidAmount == null) return null
            eligible.filter { b -> b.amount?.let { abs(it - paidAmount) <= AMOUNT_TOLERANCE } == true }
        }
        return pool.maxByOrNull { issuedAt(it) }
    }
}
