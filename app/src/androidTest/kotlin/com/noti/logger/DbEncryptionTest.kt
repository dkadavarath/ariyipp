package com.noti.logger

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.DbEncryption
import com.noti.logger.data.NotiDatabase
import com.noti.logger.reminder.DueDates
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S4: the one-off move of an installed v1.43 phone's plaintext v13 database into an encrypted v14
 * one - row counts and contents in every table, the R9 date conversion in the same pass, a kill at
 * every step of the swap, and a lost key.
 */
@RunWith(AndroidJUnit4::class)
class DbEncryptionTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "encryption-test.db"
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val dir get() = ctx.getDatabasePath(name).parentFile!!
    private val india = ZoneId.of("Asia/Kolkata")
    private val feb7 = LocalDate.of(2026, 2, 7)

    @Before fun clean() = wipe()
    @After fun cleanup() = wipe()

    private fun wipe() {
        ctx.deleteDatabase(name)
        dir.listFiles { f -> f.name.startsWith(name) }?.forEach { it.delete() }
    }

    private fun file(suffix: String = "") = File(dir, name + suffix)

    /** A v13 plaintext database with rows in every table. */
    private fun seedV13() = LegacyDb.buildV13(ctx, name) { db -> seed(db) }

    private fun seed(db: SupportSQLiteDatabase) {
        for (i in 1..40) {
            db.execSQL(
                "INSERT INTO notifications (uid, packageName, appLabel, postTime, title, text, uploaded, createdAt, contentHash) " +
                    "VALUES ('u$i', 'com.bank', 'Bank', $i, 'T$i', 'OTP 12345$i', 0, $i, 'h$i')"
            )
        }
        for (i in 1..25) {
            db.execSQL(
                "INSERT INTO relayed_messages (sender, sim, body, receivedAt, outgoing, read, dedupe, status) " +
                    "VALUES ('+97150000$i', '', 'body $i', $i, 0, 0, 'd$i', 0)"
            )
        }
        db.execSQL(
            "INSERT INTO reminder_rules (name, type, source, sourcePackage, sourceSender, enabled, matchPattern, amountPattern, " +
                "currencyPattern, datePattern, zonePattern, expiryPattern, dateFormat, dueMode, constantDay, anchorDay, offsetDays, " +
                "recurrence, recurrenceEvery, anchorDate, defaultCurrency, skipNext, createdAt, notes, category, remindDaysBefore, " +
                "payEnabled, paySource, paySender, payPackage, payMatchPattern, payAmountPattern, payMatchAmount) VALUES " +
                "('card', 'BILL', 'SMS', '', 'BANK', 1, 'due', '', '', '', '', '', '', 'FROM_MESSAGE', 1, 1, 0, 'MONTHLY', 1, 0, " +
                "'INR', 0, 1, '', '', -1, 0, 'SMS', '', '', '', '', 0)"
        )
        db.execSQL(
            "INSERT INTO reminder_bills (ruleId, name, dueAt, amount, currency, amountRaw, dueRaw, status, notes, category, " +
                "remindDaysBefore, sourceKey, remindAt, notified, createdAt, settledAt, issuedAt, settleKey, autoSettled) VALUES " +
                "(1, 'card', ${feb7.atStartOfDay(india).toInstant().toEpochMilli()}, 18402.75, 'INR', '18,402.75', '07-Feb', 0, " +
                "'', '', -1, 'k1', 0, 0, 1, 0, 1, '', 0)"
        )
        db.execSQL(
            "INSERT INTO reminder_parking (ruleId, name, zone, expiresAt, remindAt, active, incompleteNotified, renewals, startedAt, endedAt) " +
                "VALUES (0, 'car', 'A1', 5, 4, 1, 0, 0, 1, 0)"
        )
    }

    private fun openRoom(): NotiDatabase =
        Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(key)))
            .addMigrations(NotiDatabase.migration13To14({ "AED" }, fallbackZone = ZoneId.of("Asia/Dubai")))
            .build()

    private fun assertEverythingThere(db: NotiDatabase) {
        assertEquals(40, kotlinx.coroutines.runBlocking { db.notificationDao().totalCount() })
        assertEquals(25, db.relayedMessageDao().allMessages().size)
        assertEquals("body 7", db.relayedMessageDao().allMessages().first { it.dedupe == "d7" }.body)
        val rule = db.reminderRuleDao().all().single()
        assertEquals("card", rule.name)
        val bill = db.billOccurrenceDao().active().single()
        assertEquals(18402.75, bill.amount!!, 0.0)
        // R9 in the same pass: an INR bill made in India keeps its date wherever it's migrated.
        assertEquals(feb7, DueDates.fromMs(bill.dueAt))
        assertEquals(1, db.parkingSessionDao().active().size)
    }

    private fun assertEncryptedAndClean() {
        assertTrue(file().exists())
        assertFalse("must not be readable as plain SQLite", DbEncryption.isPlaintext(file()))
        assertFalse(file("-plaintext").exists())
        assertFalse(file("-encrypting").exists())
    }

    @Test
    fun a_v13_plaintext_database_moves_to_encrypted_v14_with_every_row() {
        seedV13()
        assertTrue(DbEncryption.isPlaintext(file()))
        DbEncryption.prepare(ctx, key, name)
        assertEncryptedAndClean()
        val db = openRoom()
        assertEverythingThere(db)
        db.close()
        // A second start finds it already encrypted and leaves it be.
        DbEncryption.prepare(ctx, key, name)
        openRoom().also { assertEverythingThere(it) }.close()
    }

    @Test
    fun a_kill_after_any_step_loses_nothing() {
        for (step in 1..3) {
            wipe()
            seedV13()
            try {
                DbEncryption.prepare(ctx, key, name, failAfter = step)
                error("step $step should have thrown")
            } catch (e: DbEncryption.SimulatedCrash) {
                // the process "died" here
            }
            // Next launch.
            DbEncryption.prepare(ctx, key, name)
            assertEncryptedAndClean()
            openRoom().also { assertEverythingThere(it) }.close()
        }
    }

    @Test
    fun a_lost_key_starts_fresh_and_keeps_the_old_file() {
        seedV13()
        DbEncryption.prepare(ctx, key, name)
        var reset = false
        val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        DbEncryption.prepare(ctx, otherKey, name, onEvent = { if (it == DbEncryption.Event.RESET) reset = true })
        assertTrue(reset)
        assertFalse(file().exists())
        assertTrue("unreadable file kept", dir.listFiles()!!.any { it.name.startsWith("$name-unreadable-") })
        val fresh = Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(otherKey))).build()
        assertEquals(0, kotlinx.coroutines.runBlocking { fresh.notificationDao().totalCount() })
        fresh.close()
    }

    @Test
    fun encrypted_database_is_fast_enough() {
        // Same workload on a plaintext copy first, as the baseline the log line compares against.
        seedV13()
        val plain = Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .addMigrations(NotiDatabase.migration13To14({ "AED" })).build()
        val (plainInsert, plainQuery) = workload(plain)
        plain.close()
        wipe()
        seedV13()
        DbEncryption.prepare(ctx, key, name)
        val db = openRoom()
        val (insertMs, queryMs) = workload(db)
        android.util.Log.i(
            "DbEncryptionTest",
            "1000 inserts: $insertMs ms encrypted vs $plainInsert ms plain; 50 page queries: $queryMs ms vs $plainQuery ms",
        )
        assertTrue("inserts took $insertMs ms", insertMs < 20_000)
        assertTrue("queries took $queryMs ms", queryMs < 10_000)
        db.close()
    }

    private fun workload(db: NotiDatabase): Pair<Long, Long> {
        val dao = db.notificationDao()
        val t0 = System.nanoTime()
        kotlinx.coroutines.runBlocking {
            for (i in 1..1000) {
                dao.insert(
                    com.noti.logger.data.NotificationEntity(
                        uid = "p$i", packageName = "com.app", appLabel = "App", postTime = i.toLong(),
                        title = "t", text = "x", bigText = null, subText = null, category = null,
                        sbnKey = null, uploaded = 0, createdAt = i.toLong(),
                    )
                )
            }
        }
        val insertMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        repeat(50) { kotlinx.coroutines.runBlocking { dao.pendingBatch(100) } }
        val queryMs = (System.nanoTime() - t1) / 1_000_000
        return insertMs to queryMs
    }

    // ---- v1.6 regressions ----

    @Test
    fun a_settings_reset_no_longer_loses_the_database_key() {
        val first = DbEncryption.key(ctx)
        // What Settings.openPrefs does when it can't read its store: everything in it is gone.
        com.noti.logger.config.Settings.get(ctx).dbKeySync = ""
        com.noti.logger.data.DbKeyStore.clearCache()
        assertTrue("the key file still has it", first.contentEquals(DbEncryption.key(ctx)))
        // ...and the settings copy is restored from it.
        assertTrue(com.noti.logger.config.Settings.get(ctx).dbKey.isNotBlank())
    }

    @Test
    fun only_a_wrong_key_counts_as_lost() {
        seedV13()
        DbEncryption.prepare(ctx, key, name)
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertEquals(DbEncryption.Probe.WRONG_KEY, DbEncryption.probe(file(), other))
        assertEquals(DbEncryption.Probe.OK, DbEncryption.probe(file(), key))
        // A path that can't be opened at all is "something else", not a lost key.
        assertEquals(DbEncryption.Probe.OTHER, DbEncryption.probe(File(dir, "no-such-dir/x.db"), key))
    }

    @Test
    fun a_set_aside_database_comes_back_when_its_key_does() {
        seedV13()
        DbEncryption.prepare(ctx, key, name)
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        DbEncryption.prepare(ctx, other, name) // key "lost": set aside
        assertFalse(file().exists())
        DbEncryption.prepare(ctx, key, name)   // key back: re-adopted
        assertTrue(file().exists())
        assertFalse(dir.listFiles()!!.any { it.name.startsWith("$name-unreadable-") })
        openRoom().also { assertEverythingThere(it) }.close()
    }

    @Test
    fun a_set_aside_database_opens_again_with_an_earlier_key_file() {
        // The keystore failed once: the key file was set aside and a new key made. The database
        // went aside as unreadable. Once the old key file reads again, the database comes back,
        // re-encrypted to the key in use now.
        seedV13()
        DbEncryption.prepare(ctx, key, name, oldKeys = { emptyList() })
        val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        DbEncryption.prepare(ctx, newKey, name, oldKeys = { emptyList() })
        assertFalse(file().exists())
        DbEncryption.prepare(ctx, newKey, name, oldKeys = { listOf(key) })
        assertTrue(file().exists())
        assertEquals(DbEncryption.Probe.OK, DbEncryption.probe(file(), newKey))
        Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(newKey)))
            .addMigrations(NotiDatabase.migration13To14({ "AED" }, fallbackZone = ZoneId.of("Asia/Dubai")))
            .build().also { assertEverythingThere(it) }.close()
    }

    @Test
    fun a_set_aside_database_keeps_writes_still_in_its_log() {
        seedV13()
        DbEncryption.prepare(ctx, key, name, oldKeys = { emptyList() })
        openRoom().close() // migrated to v14
        // A write that is only in -wal: a connection with checkpoints off, left open.
        DbEncryption.loadLibrary()
        val live = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            file().path, DbEncryption.passphrase(key), null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null,
        )
        live.rawQuery("PRAGMA journal_mode = WAL", null).use { it.moveToFirst() }
        live.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { it.moveToFirst() }
        live.execSQL("INSERT INTO relayed_messages (sender, sim, body, receivedAt, outgoing, read, dedupe, status) VALUES ('+1', '', 'only in the log', 1, 0, 1, 'wal1', 0)")
        assertTrue("the write sits in -wal", file("-wal").length() > 0)
        try {
            DbEncryption.prepare(ctx, ByteArray(32).also { SecureRandom().nextBytes(it) }, name, oldKeys = { emptyList() }) // key "lost"
            assertTrue("its log went aside with it", dir.listFiles()!!.any { it.name.startsWith("$name-unreadable-") && it.name.endsWith("-wal") })
            DbEncryption.prepare(ctx, key, name, oldKeys = { emptyList() }) // key back
            val db = openRoom()
            assertTrue(db.relayedMessageDao().allMessages().any { it.body == "only in the log" })
            db.close()
        } finally {
            live.close()
        }
    }

    @Test
    fun a_recovered_history_comes_back_with_what_arrived_since() {
        seedV13()
        DbEncryption.prepare(ctx, key, name, oldKeys = { emptyList() })
        openRoom().also { it.reminderRuleDao().all() }.close() // opened (Room migrates on first use) to v14
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        DbEncryption.prepare(ctx, other, name, oldKeys = { emptyList() }) // key "lost": fresh database
        // An SMS lands in the fresh database before the key comes back.
        Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(other))).build().also { fresh ->
                fresh.relayedMessageDao().insert(com.noti.logger.data.RelayedMessageEntity(sender = "+1", sim = "", body = "arrived meanwhile", receivedAt = 99, dedupe = "mw"))
            }.close()
        // The old key comes back as an earlier key; the current one is still `other`.
        var restored = false
        DbEncryption.prepare(ctx, other, name, oldKeys = { listOf(key) }, onEvent = { if (it == DbEncryption.Event.RESTORED) restored = true })
        assertTrue("restored; log: " + com.noti.shared.Diag.snapshot().take(4).joinToString(" / "), restored)
        val db = Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(other))).build()
        assertEquals("card", db.reminderRuleDao().all().single().name)
        val bodies = db.relayedMessageDao().allMessages().map { it.body }
        assertEquals(26, bodies.size)
        assertTrue("old history back", "body 7" in bodies)
        assertTrue("and the new message kept", "arrived meanwhile" in bodies)
        db.close()
        assertFalse("nothing left aside", dir.listFiles()!!.any { it.name.startsWith("$name-unreadable-") })
    }

    @Test
    fun an_old_set_aside_key_is_aged_by_its_name_not_its_file_time() {
        val f = File(dir, "db.key.unreadable-${System.currentTimeMillis()}")
        f.writeText("x")
        f.setLastModified(System.currentTimeMillis() - 200L * 24 * 60 * 60_000L) // a months-old key, renamed by v1.7
        try {
            assertTrue(System.currentTimeMillis() - com.noti.logger.data.DbKeyStore.asideSince(f) < 60_000L)
        } finally { f.delete() }
    }

    @Test
    fun a_recovered_history_never_rolls_back_reminders_made_since() {
        seedV13()
        DbEncryption.prepare(ctx, key, name, oldKeys = { emptyList() })
        openRoom().also { it.reminderRuleDao().all() }.close()
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        DbEncryption.prepare(ctx, other, name, oldKeys = { emptyList() }) // key "lost"
        // Since the reset: the user restored a backup / rebuilt the rulebook.
        Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(other))).build().also { fresh ->
                fresh.reminderRuleDao().insert(com.noti.logger.data.ReminderRuleEntity(name = "rebuilt", type = "BILL", source = "SMS"))
            }.close()
        DbEncryption.prepare(ctx, other, name, oldKeys = { listOf(key) })
        val db = Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(other))).build()
        assertEquals("today's rulebook kept", listOf("rebuilt"), db.reminderRuleDao().all().map { it.name })
        assertTrue("the old messages are back", db.relayedMessageDao().allMessages().any { it.body == "body 7" })
        db.close()
        assertFalse("nothing left aside", dir.listFiles()!!.any { it.name.startsWith("$name-unreadable-") || it.name.contains("-replaced-") })
    }

    @Test
    fun an_older_schema_copy_is_upgraded_then_merged() {
        // Set aside while still v13 (never opened by Room after encryption).
        seedV13()
        DbEncryption.prepare(ctx, key, name, oldKeys = { emptyList() })
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        DbEncryption.prepare(ctx, other, name, oldKeys = { emptyList() })
        Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(other))).build().also { fresh ->
                fresh.relayedMessageDao().insert(com.noti.logger.data.RelayedMessageEntity(sender = "+1", sim = "", body = "new", receivedAt = 1, dedupe = "n"))
            }.close()
        val saved = DbEncryption.upgrader
        DbEncryption.upgrader = { file ->
            Room.databaseBuilder(ctx, NotiDatabase::class.java, file.name)
                .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(other)))
                .addMigrations(NotiDatabase.migration13To14({ "AED" }, fallbackZone = ZoneId.of("Asia/Dubai"))).build()
                .also { it.openHelper.writableDatabase }.close()
        }
        try {
            DbEncryption.prepare(ctx, other, name, oldKeys = { listOf(key) })
        } finally { DbEncryption.upgrader = saved }
        val db = Room.databaseBuilder(ctx, NotiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(DbEncryption.passphrase(other))).build()
        val bodies = db.relayedMessageDao().allMessages().map { it.body }
        assertTrue("old history merged after the upgrade", "body 7" in bodies && "new" in bodies)
        assertEquals("card", db.reminderRuleDao().all().single().name) // none since the reset: the old ones come back
        db.close()
        assertFalse(dir.listFiles()!!.any { it.name.startsWith("$name-unreadable-") })
    }
}
