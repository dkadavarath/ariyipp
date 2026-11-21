package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.backup.Backup
import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ParkingSessionEntity
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.reminder.BillStatus
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reminders surviving a backup and restore.
 *
 * The rulebook is the part that matters most: hand-written regexes tuned against real messages
 * over weeks, which nothing else in the app can reconstruct. Bills and their history come with it
 * so a restored phone shows what was paid and what is outstanding, not an empty tab.
 */
@RunWith(AndroidJUnit4::class)
class BackupRemindersTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db = NotiDatabase.get(ctx)

    @Before fun clear() = wipe()
    @After fun cleanup() = wipe()

    private fun wipe() {
        db.reminderRuleDao().deleteAll()
        db.billOccurrenceDao().deleteAll()
        db.parkingSessionDao().deleteAll()
    }

    private fun seed(): Long {
        val ruleId = db.reminderRuleDao().insert(
            ReminderRuleEntity(
                name = "HDFC Card", type = "BILL", source = "SMS", sourceSender = "HDFCBK",
                matchPattern = """statement.*generated""",
                amountPattern = """INR\s*([\d,]+\.?\d*)""",
                datePattern = """due by\s+(\d{2}-\w{3})""",
                dateFormat = "dd-MMM", recurrence = "DAILY", recurrenceEvery = 28,
                anchorDate = 1_700_000_000_000L, offsetDays = 10,
                notes = "off the joint account", category = "Cards", remindDaysBefore = 3,
                defaultAmount = 499.0,
                payEnabled = 1, paySource = "SMS", paySender = "HDFCBK", payMatchPattern = "Payment of",
                payAmountPattern = """Payment of INR\s*([\d,]+\.?\d*)""", payMatchAmount = 1,
            )
        )
        db.billOccurrenceDao().insert(
            BillOccurrenceEntity(
                ruleId = ruleId, name = "HDFC Card", dueAt = 1_800_000_000_000L,
                amount = 12480.5, currency = "INR", amountRaw = "12,480.50",
                status = BillStatus.ACTIVE, sourceKey = "k-active",
            )
        )
        db.billOccurrenceDao().insert(
            BillOccurrenceEntity(
                ruleId = ruleId, name = "HDFC Card", dueAt = 1_700_000_000_000L,
                amount = 900.0, currency = "INR", status = BillStatus.PAID,
                sourceKey = "k-paid", settledAt = 1_700_500_000_000L,
                issuedAt = 1_699_000_000_000L, settleKey = "pay-key", autoSettled = 1,
            )
        )
        // A one-off added by hand, which has no rule behind it.
        db.billOccurrenceDao().insert(
            BillOccurrenceEntity(
                ruleId = 0, name = "Plumber", dueAt = 1_800_100_000_000L,
                amount = 250.0, currency = "AED", status = BillStatus.ACTIVE, sourceKey = "k-manual",
            )
        )
        db.parkingSessionDao().insert(
            ParkingSessionEntity(name = "Street Parking", zone = "BAY-12", expiresAt = 1_800_200_000_000L, renewals = 2)
        )
        return ruleId
    }

    @Test fun rules_bills_history_and_manual_entries_all_come_back() {
        seed()
        val json = Backup.export(ctx)
        wipe()
        assertEquals(0, db.reminderRuleDao().all().size)

        Backup.import(ctx, json)

        val rule = db.reminderRuleDao().all().single()
        assertEquals("HDFC Card", rule.name)
        // The regexes are the whole point - they must survive character for character.
        assertEquals("""INR\s*([\d,]+\.?\d*)""", rule.amountPattern)
        assertEquals("""due by\s+(\d{2}-\w{3})""", rule.datePattern)
        assertEquals("dd-MMM", rule.dateFormat)
        assertEquals(28, rule.recurrenceEvery)
        assertEquals(10, rule.offsetDays)
        assertEquals(1_700_000_000_000L, rule.anchorDate)
        assertEquals("off the joint account", rule.notes)
        assertEquals("Cards", rule.category)
        assertEquals(3, rule.remindDaysBefore)
        // v1.40+: the manual amount and the whole payment resolver come back too.
        assertEquals(499.0, rule.defaultAmount!!, 0.0)
        assertEquals(1, rule.payEnabled)
        assertEquals("HDFCBK", rule.paySender)
        assertEquals("Payment of", rule.payMatchPattern)
        assertEquals("""Payment of INR\s*([\d,]+\.?\d*)""", rule.payAmountPattern)
        assertEquals(1, rule.payMatchAmount)

        val active = db.billOccurrenceDao().active()
        assertEquals(2, active.size) // the card bill and the hand-added one
        assertTrue(active.any { it.name == "Plumber" && it.ruleId == 0L })

        val history = db.billOccurrenceDao().history()
        assertEquals(1, history.size)
        assertEquals(BillStatus.PAID, history.single().status)
        // ...and a bill settled automatically stays recorded as such, with its payment's key, so a
        // rescan after restore can't use that payment again.
        assertEquals(1, history.single().autoSettled)
        assertEquals("pay-key", history.single().settleKey)
        assertEquals(1_699_000_000_000L, history.single().issuedAt)

        val parking = db.parkingSessionDao().active().single()
        assertEquals("BAY-12", parking.zone)
        assertEquals(2, parking.renewals)
    }

    @Test fun a_restored_bill_still_points_at_its_rule() {
        // Rule ids are reassigned on restore, so bills are re-linked by name. Getting this wrong
        // would silently attach a bill to whatever rule landed in that row.
        seed()
        val json = Backup.export(ctx)
        wipe()
        // Insert a decoy first so the restored rule cannot land on its original id.
        db.reminderRuleDao().insert(ReminderRuleEntity(name = "decoy", type = "BILL", source = "MANUAL"))
        Backup.import(ctx, json)

        val rule = db.reminderRuleDao().all().single { it.name == "HDFC Card" }
        val carded = db.billOccurrenceDao().active().single { it.name == "HDFC Card" }
        assertEquals(rule.id, carded.ruleId)
        assertNotNull(db.billOccurrenceDao().lastSettledForRule(rule.id))
    }

    @Test fun an_older_backup_without_reminders_leaves_the_rulebook_alone() {
        // Restoring a pre-v2 file must not wipe a working rulebook just because the file predates
        // the feature.
        seed()
        val legacy = """{"version":1,"settings":${settingsJsonFrom(Backup.export(ctx))},"messages":[]}"""
        Backup.import(ctx, legacy)
        assertEquals(1, db.reminderRuleDao().all().size)
        assertEquals(2, db.billOccurrenceDao().active().size)
    }

    /** Lifts the settings object out of a real export so the legacy payload above is valid. */
    private fun settingsJsonFrom(export: String): String {
        val start = export.indexOf("\"settings\":") + "\"settings\":".length
        var depth = 0
        var i = start
        while (i < export.length) {
            if (export[i] == '{') depth++
            if (export[i] == '}') {
                depth--
                if (depth == 0) return export.substring(start, i + 1)
            }
            i++
        }
        error("no settings object in export")
    }

    /** Every setting that is the user's choice now travels in the backup; device state does not. */
    @Test fun the_rest_of_the_settings_come_back_too() {
        val s = com.noti.logger.config.Settings.get(ctx)
        val saved = listOf(s.reminderHour, s.reminderDaysBefore, s.companionBatteryPct)
        val savedFlags = listOf(s.otpCopyEnabled, s.heartbeatEnabled, s.remindersCompact, s.remindersShowTotal)
        val savedMode = s.parkingAlarmMode
        val savedCurrency = s.defaultCurrency
        try {
            s.defaultCurrency = "EUR"
            s.reminderHour = 7; s.reminderDaysBefore = 4; s.companionBatteryPct = 25
            s.otpCopyEnabled = false; s.heartbeatEnabled = false; s.remindersCompact = true; s.remindersShowTotal = false
            s.parkingAlarmMode = "SYSTEM_CLOCK"
            val json = Backup.export(ctx)
            s.reminderHour = 9; s.reminderDaysBefore = 1; s.companionBatteryPct = 50
            s.otpCopyEnabled = true; s.heartbeatEnabled = true; s.remindersCompact = false; s.remindersShowTotal = true
            s.parkingAlarmMode = "IN_APP"
            s.defaultCurrency = ""

            Backup.import(ctx, json)

            assertEquals("EUR", s.defaultCurrency)

            assertEquals(7, s.reminderHour)
            assertEquals(4, s.reminderDaysBefore)
            assertEquals(25, s.companionBatteryPct)
            assertEquals(false, s.otpCopyEnabled)
            assertEquals(false, s.heartbeatEnabled)
            assertEquals(true, s.remindersCompact)
            assertEquals(false, s.remindersShowTotal)
            assertEquals("SYSTEM_CLOCK", s.parkingAlarmMode)
        } finally {
            s.reminderHour = saved[0]; s.reminderDaysBefore = saved[1]; s.companionBatteryPct = saved[2]
            s.otpCopyEnabled = savedFlags[0]; s.heartbeatEnabled = savedFlags[1]
            s.remindersCompact = savedFlags[2]; s.remindersShowTotal = savedFlags[3]
            s.parkingAlarmMode = savedMode
            s.defaultCurrency = savedCurrency
            Thread.sleep(3000)
        }
    }

    /** A backup from before these fields existed restores, and leaves the current values alone. */
    @Test fun an_older_backup_keeps_current_settings() {
        val s = com.noti.logger.config.Settings.get(ctx)
        val savedHour = s.reminderHour
        try {
            s.reminderHour = 6
            val json = Backup.export(ctx)
            val old = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.let { root ->
                val settings = root["settings"]!!.jsonObject.filterKeys { it != "reminderHour" }
                kotlinx.serialization.json.JsonObject(root + ("settings" to kotlinx.serialization.json.JsonObject(settings)))
            }.toString()
            s.reminderHour = 8
            Backup.import(ctx, old)
            assertEquals(8, s.reminderHour)
        } finally {
            s.reminderHour = savedHour
            Thread.sleep(3000)
        }
    }
}
