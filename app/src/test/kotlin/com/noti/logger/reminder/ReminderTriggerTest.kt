package com.noti.logger.reminder

import com.noti.logger.data.ReminderRuleEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTriggerTest {

    private val smsBill = ReminderRuleEntity(name = "ICICI", type = "BILL", source = "SMS", sourceSender = "ICICIB")
    private val appBill = ReminderRuleEntity(name = "DEWA", type = "BILL", source = "NOTIFICATION", sourcePackage = "ae.dewa")
    private val manualPaidBySms = ReminderRuleEntity(
        name = "Rent", type = "BILL", source = "MANUAL",
        payEnabled = 1, paySource = "SMS", paySender = "ENBD", payMatchPattern = "debited",
    )
    private val manualPaidByApp = manualPaidBySms.copy(paySource = "NOTIFICATION", paySender = "", payPackage = "com.bank")

    @Test fun an_sms_from_a_rule_sender_is_eligible_in_any_operator_dress() {
        assertTrue(ReminderTrigger.matchesSms(listOf(smsBill), "VM-ICICIB-S"))
        assertTrue(ReminderTrigger.matchesSms(listOf(smsBill), "AD-icicib"))
        assertFalse(ReminderTrigger.matchesSms(listOf(smsBill), "Mom"))
    }

    @Test fun an_sms_rule_without_a_sender_reads_every_sms() {
        assertTrue(ReminderTrigger.matchesSms(listOf(smsBill.copy(sourceSender = "")), "anyone"))
    }

    @Test fun a_notification_from_the_rule_app_is_eligible_and_no_other() {
        assertTrue(ReminderTrigger.matchesNotification(listOf(appBill), "ae.dewa"))
        assertFalse(ReminderTrigger.matchesNotification(listOf(appBill), "com.whatsapp"))
        assertFalse(ReminderTrigger.matchesNotification(listOf(appBill.copy(sourcePackage = "")), "com.whatsapp"))
    }

    @Test fun sources_dont_cross() {
        assertFalse(ReminderTrigger.matchesNotification(listOf(smsBill), "ICICIB"))
        assertFalse(ReminderTrigger.matchesSms(listOf(appBill), "ae.dewa"))
    }

    @Test fun the_payment_side_counts_too_even_on_a_manual_bill() {
        assertTrue(ReminderTrigger.matchesSms(listOf(manualPaidBySms), "AD-ENBD"))
        assertTrue(ReminderTrigger.matchesNotification(listOf(manualPaidByApp), "com.bank"))
    }

    @Test fun a_payment_side_that_is_off_or_empty_does_not() {
        assertFalse(ReminderTrigger.matchesSms(listOf(manualPaidBySms.copy(payEnabled = 0)), "AD-ENBD"))
        assertFalse(ReminderTrigger.matchesSms(listOf(manualPaidBySms.copy(payMatchPattern = "")), "AD-ENBD"))
    }
}
