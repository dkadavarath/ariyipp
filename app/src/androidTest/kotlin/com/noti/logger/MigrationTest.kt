package com.noti.logger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.NotiDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the migration chain by hand-building a v1 database matching Room's generated v1 schema
 * and opening it with Room at the current version: v1→v2 adds contentHash (+ index) and preserves
 * existing outbox rows, and every later migration has to line up too, because Room validates the
 * final schema against the entities on open. That validation is the real value here - it is what
 * catches a hand-written CREATE TABLE that has drifted from its @Entity.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before fun clean() { ctx.deleteDatabase(dbName) }
    @After fun cleanup() { ctx.deleteDatabase(dbName) }

    @Test
    fun migrate_1_to_2_preserves_rows_and_adds_contentHash() {
        // ---- Build a v1 database exactly as Room generated it ----
        val path = ctx.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val v1 = SQLiteDatabase.openOrCreateDatabase(path, null)
        v1.execSQL(
            "CREATE TABLE `notifications` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, " +
                "`packageName` TEXT NOT NULL, `appLabel` TEXT, `postTime` INTEGER NOT NULL, " +
                "`title` TEXT, `text` TEXT, `bigText` TEXT, `subText` TEXT, `category` TEXT, " +
                "`sbnKey` TEXT, `uploaded` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        v1.execSQL("CREATE INDEX `index_notifications_uploaded` ON `notifications` (`uploaded`)")
        v1.execSQL("CREATE INDEX `index_notifications_postTime` ON `notifications` (`postTime`)")
        v1.execSQL("CREATE UNIQUE INDEX `index_notifications_uid` ON `notifications` (`uid`)")
        v1.execSQL(
            "INSERT INTO notifications " +
                "(uid,packageName,appLabel,postTime,title,text,bigText,subText,category,sbnKey,uploaded,createdAt) " +
                "VALUES ('u1','com.app','App',100,'T','B',NULL,NULL,'msg','k',0,100)"
        )
        v1.version = 1
        v1.close()

        // ---- Open with Room + migrations to the current version; Room validates the schema ----
        val db = Room.databaseBuilder(ctx, NotiDatabase::class.java, dbName)
            .addMigrations(
                NotiDatabase.MIGRATION_1_2, NotiDatabase.MIGRATION_2_3,
                NotiDatabase.MIGRATION_3_4, NotiDatabase.MIGRATION_4_5,
                NotiDatabase.MIGRATION_5_6, NotiDatabase.MIGRATION_6_7,
                NotiDatabase.MIGRATION_7_8, NotiDatabase.MIGRATION_8_9,
                NotiDatabase.MIGRATION_9_10, NotiDatabase.MIGRATION_10_11,
                NotiDatabase.MIGRATION_11_12, NotiDatabase.MIGRATION_12_13,
                NotiDatabase.migration13To14({ "AED" }),
            )
            .build()
        runBlocking {
            val dao = db.notificationDao()
            assertEquals("existing row preserved", 1, dao.totalCount())
            // Migrated row got contentHash '' (the ADD COLUMN default) and createdAt 100.
            assertEquals(1, dao.countRecentByHash("", 0))
            // v2→v3 created the relayed_messages table (querying it must not throw).
            assertEquals(0, db.relayedMessageDao().conversations().size)
            // v8→v9 created the three Reminder tables. Room validates the whole schema on open, so
            // reaching this point already proves the hand-written CREATE statements match the
            // entities - these queries additionally prove the tables are usable, not just present.
            assertEquals(0, db.reminderRuleDao().all().size)
            assertEquals(0, db.billOccurrenceDao().active().size)
            assertEquals(0, db.parkingSessionDao().active().size)
            // v9→v10 added the recurrence interval and anchor date; a rule round-trips through
            // them, which is what proves the ALTER statements match the entity.
            val id = db.reminderRuleDao().insert(
                com.noti.logger.data.ReminderRuleEntity(
                    name = "every 28 days", type = "BILL", source = "MANUAL",
                    recurrence = "DAILY", recurrenceEvery = 28, anchorDate = 1_700_000_000_000L,
                )
            )
            val back = db.reminderRuleDao().byId(id)!!
            assertEquals(28, back.recurrenceEvery)
            assertEquals(1_700_000_000_000L, back.anchorDate)
            // v10→v11 columns default in a way that preserves the old behaviour.
            assertEquals("", back.category)
            assertEquals(-1, back.remindDaysBefore)
            // v11→v12: the manual rule's amount round-trips; unset stays null.
            assertEquals(null, back.defaultAmount)
            db.reminderRuleDao().update(back.copy(defaultAmount = 499.0))
            assertEquals(499.0, db.reminderRuleDao().byId(id)!!.defaultAmount!!, 0.0)
            // v12→v13: the payment resolver defaults to off, from SMS, amount not matched.
            val r13 = db.reminderRuleDao().byId(id)!!
            assertEquals(0, r13.payEnabled)
            assertEquals("SMS", r13.paySource)
            assertEquals(0, r13.payMatchAmount)
        }
        db.close()
    }

    /** The 11→12 backfill: a manual rule picks up the amount from its newest bill that has one;
     *  message-driven rules and rules with no amount anywhere are left null. Run against just the
     *  columns the statement touches, since building a full v11 database by hand proves nothing more. */
    @Test
    fun migrate_11_to_12_backfills_manual_rule_amounts() {
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx).name(null)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        val db = helper.writableDatabase
        db.execSQL("CREATE TABLE reminder_rules (id INTEGER PRIMARY KEY, source TEXT NOT NULL)")
        db.execSQL("CREATE TABLE reminder_bills (id INTEGER PRIMARY KEY, ruleId INTEGER NOT NULL, amount REAL, dueAt INTEGER NOT NULL)")
        db.execSQL("INSERT INTO reminder_rules VALUES (1, 'MANUAL'), (2, 'SMS'), (3, 'MANUAL')")
        // Rule 1: an older bill at 100, the newest with no amount, a middle one corrected to 250.
        db.execSQL("INSERT INTO reminder_bills (ruleId, amount, dueAt) VALUES (1, 100, 10), (1, 250, 20), (1, NULL, 30)")
        db.execSQL("INSERT INTO reminder_bills (ruleId, amount, dueAt) VALUES (2, 999, 10), (3, NULL, 10)")

        NotiDatabase.MIGRATION_11_12.migrate(db)

        fun amountOf(id: Int): Double? = db.query("SELECT defaultAmount FROM reminder_rules WHERE id = $id").use {
            it.moveToFirst(); if (it.isNull(0)) null else it.getDouble(0)
        }
        assertEquals(250.0, amountOf(1)!!, 0.0)
        assertEquals(null, amountOf(2))
        assertEquals(null, amountOf(3))
        helper.close()
    }
}
