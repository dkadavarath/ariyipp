package com.noti.logger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.NotiDatabase
import com.noti.logger.reminder.DueDates
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** v13 → v14: due dates become plain dates, read in their currency's zone. */
@RunWith(AndroidJUnit4::class)
class ReminderMigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "reminder-migration-test.db"
    private val india = ZoneId.of("Asia/Kolkata")
    private val uae = ZoneId.of("Asia/Dubai")
    private val berlin = ZoneId.of("Europe/Berlin")

    @After fun cleanup() { ctx.deleteDatabase(name) }

    private fun midnight(d: LocalDate, z: ZoneId) = d.atStartOfDay(z).toInstant().toEpochMilli()

    @Test
    fun dates_convert_by_currency_zone_and_new_columns_default() {
        val feb7 = LocalDate.of(2026, 2, 7)
        val mar1 = LocalDate.of(2026, 3, 1)
        LegacyDb.buildV13(ctx, name) { db ->
            // Made in India, an INR bill; made in Germany, a EUR one; a blank currency (default AED,
            // made here in the UAE); a bill with no date; a rule anchored on 1 March in India.
            fun bill(key: String, due: Long, currency: String) = db.execSQL(
                "INSERT INTO reminder_bills (ruleId, name, dueAt, currency, amountRaw, dueRaw, status, notes, category, " +
                    "remindDaysBefore, sourceKey, remindAt, notified, createdAt, settledAt, issuedAt, settleKey, autoSettled) " +
                    "VALUES (0, '$key', $due, '$currency', '', '', 0, '', '', -1, '$key', 0, 0, 1, 0, 1, '', 0)"
            )
            bill("inr", midnight(feb7, india), "INR")
            bill("eur", midnight(feb7, berlin), "EUR")
            bill("blank", midnight(feb7, uae), "")
            bill("none", 0, "INR")
            db.execSQL(
                "INSERT INTO reminder_rules (name, type, source, sourcePackage, sourceSender, enabled, matchPattern, " +
                    "amountPattern, currencyPattern, datePattern, zonePattern, expiryPattern, dateFormat, dueMode, constantDay, " +
                    "anchorDay, offsetDays, recurrence, recurrenceEvery, anchorDate, defaultCurrency, skipNext, createdAt, notes, " +
                    "category, remindDaysBefore, payEnabled, paySource, paySender, payPackage, payMatchPattern, payAmountPattern, payMatchAmount) " +
                    "VALUES ('rent', 'BILL', 'MANUAL', '', '', 1, '', '', '', '', '', '', '', 'CONSTANT', 1, 1, 0, 'MONTHLY', 1, " +
                    "${midnight(mar1, india)}, 'Rs', 0, 1, '', '', -1, 0, 'SMS', '', '', '', '', 0)"
            )
            db.execSQL("INSERT INTO reminder_parking (ruleId, name, zone, expiresAt, remindAt, active, incompleteNotified, renewals, startedAt, endedAt) " +
                "VALUES (0, 'car', 'A1', 5, 4, 1, 0, 0, 1, 0)")
        }

        // Migrated on a phone that is now in the UAE, with AED as the default currency. Real zone
        // lookup (ICU) for the currencies, so this also checks DueDates.zonesForCurrency.
        val db = Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .addMigrations(NotiDatabase.migration13To14({ "AED" }, fallbackZone = uae))
            .build()
        val bills = db.billOccurrenceDao().active().associateBy { it.name }
        assertEquals("INR in India's zone", feb7, DueDates.fromMs(bills.getValue("inr").dueAt))
        assertEquals("EUR spans zones: nearest midnight", feb7, DueDates.fromMs(bills.getValue("eur").dueAt))
        assertEquals("blank = default AED", feb7, DueDates.fromMs(bills.getValue("blank").dueAt))
        assertEquals("no date stays none", 0L, bills.getValue("none").dueAt)
        assertEquals(0, bills.getValue("inr").editedFields)
        assertEquals(mar1, DueDates.fromMs(db.reminderRuleDao().all().single().anchorDate))
        assertEquals(0L, db.parkingSessionDao().active().single().rangFor)
        db.close()
    }
}
