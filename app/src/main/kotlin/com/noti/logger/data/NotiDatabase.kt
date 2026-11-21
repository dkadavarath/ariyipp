package com.noti.logger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationEntity::class,
        RelayedMessageEntity::class,
        ReminderRuleEntity::class,
        BillOccurrenceEntity::class,
        ParkingSessionEntity::class,
    ],
    version = 14,
    // Exported to app/schemas (committed) - MigrationTestHelper validates migrations against them.
    exportSchema = true,
)
abstract class NotiDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun relayedMessageDao(): RelayedMessageDao
    abstract fun reminderRuleDao(): ReminderRuleDao
    abstract fun billOccurrenceDao(): BillOccurrenceDao
    abstract fun parkingSessionDao(): ParkingSessionDao

    companion object {
        @Volatile
        private var INSTANCE: NotiDatabase? = null

        /** v1 → v2: add contentHash column (+ index) for time-windowed duplicate detection. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_contentHash ON notifications(contentHash)")
            }
        }

        /** v2 → v3: add the relayed_messages table (chat history for messages pushed from sndi). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS relayed_messages (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        sender TEXT NOT NULL,
                        sim TEXT NOT NULL,
                        body TEXT NOT NULL,
                        receivedAt INTEGER NOT NULL,
                        outgoing INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_relayed_messages_sender ON relayed_messages(sender)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_relayed_messages_receivedAt ON relayed_messages(receivedAt)")
            }
        }

        /** v3 → v4: add a read flag to relayed_messages. Existing rows count as already read. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE relayed_messages ADD COLUMN read INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v4 → v5: add a content-dedupe key to relayed_messages (+ index) for missed-SMS sync. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE relayed_messages ADD COLUMN dedupe TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_relayed_messages_dedupe ON relayed_messages(dedupe)")
            }
        }

        /** v5 → v6: add a delivery-status column to relayed_messages, driven by the companion's
         *  DeliveryAck (received / sent / delivered / failed). Existing rows default to pending. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE relayed_messages ADD COLUMN status INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6 → v7: add a compound (sender, receivedAt) index to relayed_messages so the
         *  "latest message per sender" correlated subquery in conversations()/searchConversations()
         *  is an index seek instead of an O(N^2) scan as history grows. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_relayed_messages_sender_receivedAt " +
                        "ON relayed_messages(sender, receivedAt)"
                )
            }
        }

        /** v7 → v8: add a compound (packageName, createdAt) index to notifications so the
         *  per-package rate-limit check in NotiListenerService (a backstop against a single
         *  noisy/malicious source flooding storage) is an index seek instead of a table scan. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_notifications_packageName_createdAt " +
                        "ON notifications(packageName, createdAt)"
                )
            }
        }

        /** v8 → v9: the Reminder module's three tables. Purely additive - nothing existing is
         *  touched, so an install that never turns the module on carries three empty tables and is
         *  otherwise unchanged. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reminder_rules (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        source TEXT NOT NULL,
                        sourcePackage TEXT NOT NULL DEFAULT '',
                        sourceSender TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        matchPattern TEXT NOT NULL DEFAULT '',
                        amountPattern TEXT NOT NULL DEFAULT '',
                        currencyPattern TEXT NOT NULL DEFAULT '',
                        datePattern TEXT NOT NULL DEFAULT '',
                        zonePattern TEXT NOT NULL DEFAULT '',
                        expiryPattern TEXT NOT NULL DEFAULT '',
                        dateFormat TEXT NOT NULL DEFAULT '',
                        dueMode TEXT NOT NULL DEFAULT 'FROM_MESSAGE',
                        constantDay INTEGER NOT NULL DEFAULT 1,
                        anchorDay INTEGER NOT NULL DEFAULT 1,
                        offsetDays INTEGER NOT NULL DEFAULT 0,
                        recurrence TEXT NOT NULL DEFAULT 'MONTHLY',
                        defaultCurrency TEXT NOT NULL DEFAULT '',
                        skipNext INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_rules_enabled ON reminder_rules(enabled)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_rules_sourcePackage ON reminder_rules(sourcePackage)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reminder_bills (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        ruleId INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL,
                        dueAt INTEGER NOT NULL DEFAULT 0,
                        amount REAL,
                        currency TEXT NOT NULL DEFAULT '',
                        amountRaw TEXT NOT NULL DEFAULT '',
                        dueRaw TEXT NOT NULL DEFAULT '',
                        status INTEGER NOT NULL DEFAULT 0,
                        sourceKey TEXT NOT NULL DEFAULT '',
                        remindAt INTEGER NOT NULL DEFAULT 0,
                        notified INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        settledAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_bills_ruleId ON reminder_bills(ruleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_bills_status ON reminder_bills(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_bills_dueAt ON reminder_bills(dueAt)")
                // Unique: re-scanning the same statement must update one row, never create a second.
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reminder_bills_sourceKey ON reminder_bills(sourceKey)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reminder_parking (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        ruleId INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL,
                        zone TEXT NOT NULL DEFAULT '',
                        expiresAt INTEGER NOT NULL DEFAULT 0,
                        remindAt INTEGER NOT NULL DEFAULT 0,
                        active INTEGER NOT NULL DEFAULT 1,
                        incompleteNotified INTEGER NOT NULL DEFAULT 0,
                        renewals INTEGER NOT NULL DEFAULT 0,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_parking_active ON reminder_parking(active)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_parking_expiresAt ON reminder_parking(expiresAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_parking_zone ON reminder_parking(zone)")
            }
        }

        /** v9 → v10: manual bills gain an interval ("every 28 days") and a date to count from, so
         *  a recurrence is no longer limited to one-unit steps off a day-of-month. Additive, with
         *  defaults matching the previous behaviour. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN recurrenceEvery INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN anchorDate INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v10 → v11: notes, a category and a per-entry reminder lead, on both rules and the bills
         *  they produce. Additive; -1 keeps the existing behaviour of using the global lead. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("reminder_rules", "reminder_bills")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN category TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN remindDaysBefore INTEGER NOT NULL DEFAULT -1")
                }
            }
        }

        /** v11 → v12: a repeating manual bill's amount. It used to be dropped on save - the rule had
         *  nowhere to keep it - so every occurrence came out blank. Backfilled from the newest
         *  occurrence the user already corrected by hand, so existing rules carry it from now on. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN defaultAmount REAL")
                db.execSQL(
                    "UPDATE reminder_rules SET defaultAmount = (SELECT b.amount FROM reminder_bills b " +
                        "WHERE b.ruleId = reminder_rules.id AND b.amount IS NOT NULL " +
                        "ORDER BY b.dueAt DESC LIMIT 1) WHERE source = 'MANUAL'"
                )
            }
        }

        /** v12 → v13: the payment resolver - its options on the rule, and on each bill when it was
         *  issued and which payment (if any) settled it. Existing bills count as issued when they
         *  were created, which is the best record there is for them. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN payEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN paySource TEXT NOT NULL DEFAULT 'SMS'")
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN paySender TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN payPackage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN payMatchPattern TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN payAmountPattern TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reminder_rules ADD COLUMN payMatchAmount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reminder_bills ADD COLUMN issuedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reminder_bills ADD COLUMN settleKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reminder_bills ADD COLUMN autoSettled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE reminder_bills SET issuedAt = createdAt")
            }
        }

        /**
         * v13 → v14:
         *  - due dates and rule anchor dates become plain dates (UTC midnight; see DueDates). The old
         *    values were local midnight, read back in whatever zone the phone was in, so a move from
         *    India to the UAE moved every date back a day. Each is converted once, read in the zone of
         *    its currency's country ([zonesFor]), with the default currency for a blank one and
         *    nearest-midnight rounding when the currency spans zones or is unknown;
         *  - bills record which fields you edited (a rescan leaves those alone);
         *  - parking sessions record which reminder time an alarm already rang for.
         * [zonesFor] and [homeCurrency] are parameters so the migration test can pin them.
         */
        fun migration13To14(
            homeCurrency: () -> String,
            zonesFor: (String?) -> List<java.time.ZoneId> = { com.noti.logger.reminder.DueDates.zonesForCurrency(it) },
            fallbackZone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        ) = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminder_bills ADD COLUMN editedFields INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reminder_parking ADD COLUMN rangFor INTEGER NOT NULL DEFAULT 0")
                val home by lazy { homeCurrency() }
                fun iso(currency: String): String? =
                    com.noti.logger.reminder.FxRates.isoOf(currency, home)
                fun convert(table: String, column: String, currencyColumn: String) {
                    val rows = ArrayList<Triple<Long, Long, String>>()
                    db.query("SELECT id, $column, $currencyColumn FROM $table WHERE $column > 0").use { c ->
                        while (c.moveToNext()) rows.add(Triple(c.getLong(0), c.getLong(1), c.getString(2) ?: ""))
                    }
                    for ((id, ms, currency) in rows) {
                        val converted = com.noti.logger.reminder.DueDates.legacyToMs(ms, zonesFor(iso(currency)), fallbackZone)
                        db.execSQL("UPDATE $table SET $column = ? WHERE id = ?", arrayOf<Any>(converted, id))
                    }
                }
                convert("reminder_bills", "dueAt", "currency")
                convert("reminder_rules", "anchorDate", "defaultCurrency")
            }
        }

        fun get(context: Context): NotiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Opens the encrypted database (see DbEncryption), encrypting a plaintext one first. */
        private fun build(app: Context): NotiDatabase {
            val key = DbEncryption.key(app)
            val encrypted = try {
                DbEncryption.prepare(app, key, onReset = { com.noti.logger.alert.Alerter.alertDatabaseReset(app) })
                true
            } catch (e: DbEncryption.EncryptionPending) {
                false // the plaintext stays usable for this run; the next start tries again
            }
            return Room.databaseBuilder(app, NotiDatabase::class.java, DbEncryption.DB_NAME)
                .apply {
                    // A fresh array: the factory may wipe the one it's given once the database is open.
                    if (encrypted) openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(DbEncryption.passphrase(key)))
                }
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    migration13To14({ com.noti.logger.reminder.FxRates.home(app) }),
                ).build()
        }
    }
}
