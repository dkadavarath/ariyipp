package com.noti.logger

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noti.logger.data.NotiDatabase
import com.noti.logger.reminder.DueDates
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations checked against the schemas Room itself exported (app/schemas), rather than a
 * hand-written copy: runMigrationsAndValidate fails if the migrated tables differ in any column,
 * type, default or index from what Room expects at the new version.
 */
@RunWith(AndroidJUnit4::class)
class SchemaMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NotiDatabase::class.java)

    private val name = "schema-migration-test.db"

    @Test
    fun migrate_13_to_14_matches_the_exported_schema_and_converts_dates() {
        val india = ZoneId.of("Asia/Kolkata")
        val feb7 = LocalDate.of(2026, 2, 7)
        helper.createDatabase(name, 13).use { db ->
            db.execSQL(
                "INSERT INTO reminder_bills (ruleId, name, dueAt, currency, amountRaw, dueRaw, status, notes, category, " +
                    "remindDaysBefore, sourceKey, remindAt, notified, createdAt, settledAt, issuedAt, settleKey, autoSettled) " +
                    "VALUES (0, 'card', ${feb7.atStartOfDay(india).toInstant().toEpochMilli()}, 'INR', '', '', 0, '', '', -1, 'k', 0, 0, 1, 0, 1, '', 0)"
            )
        }
        helper.runMigrationsAndValidate(
            name, 14, true, NotiDatabase.migration13To14({ "AED" }, fallbackZone = ZoneId.of("Asia/Dubai")),
        ).use { db ->
            db.query("SELECT dueAt, editedFields FROM reminder_bills").use { c ->
                c.moveToFirst()
                assertEquals(feb7, DueDates.fromMs(c.getLong(0)))
                assertEquals(0, c.getInt(1))
            }
        }
    }
}
