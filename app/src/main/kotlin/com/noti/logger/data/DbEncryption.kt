package com.noti.logger.data

import android.content.Context
import com.noti.shared.Diag
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Encryption at rest for the app's database (SQLCipher, AES-256): captured notifications, chats,
 * reminders and the upload queue. The key is 32 random bytes generated on first use and kept by
 * DbKeyStore (its own file, wrapped by a hardware-keystore key, with a spare copy in the settings
 * store), so it never leaves the phone in usable form.
 *
 * [prepare] runs before Room opens the database, and does the one-off move from the plaintext file
 * earlier versions wrote:
 *  1. export the plaintext into `noti.db-encrypting` (sqlcipher_export, keeping user_version, so
 *     Room still runs any schema migration afterwards);
 *  2. check every table's row count in the copy against the original;
 *  3. move the plaintext aside (`noti.db-plaintext`), move the copy into place, delete the plaintext.
 * A failure, or the process being killed, at any step leaves a complete database to start from on
 * the next launch: before step 3 the plaintext is untouched and the partial copy is discarded; in
 * step 3 [recover] finishes the swap from whichever files survived.
 *
 * If the key is ever lost the database can't be opened. The unreadable file is moved aside with its
 * log files, untouched, Room starts an empty one, and [onReset] tells the user to restore a backup -
 * capture and relay carry on regardless. Every later start tries the set-aside file again, with the
 * current key and with any earlier key still on file (see [readoptOrCleanAside]).
 */
object DbEncryption {

    const val DB_NAME = "noti.db"
    private const val TMP_SUFFIX = "-encrypting"
    private const val PLAIN_SUFFIX = "-plaintext"
    private const val UNREADABLE_SUFFIX = "-unreadable-"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Volatile private var loaded = false

    fun loadLibrary() {
        if (!loaded) synchronized(this) {
            if (!loaded) { System.loadLibrary("sqlcipher"); loaded = true }
        }
    }

    /** This install's database key (see DbKeyStore: its own keystore-wrapped file, not only the
     *  settings store, which can reset itself). */
    fun key(context: Context): ByteArray = DbKeyStore.key(context)

    /** A file set aside as unreadable is deleted after this long if it still doesn't open. */
    const val ASIDE_KEEP_MS = 30L * 24 * 60 * 60_000L

    /**
     * What SQLCipher is given to open the database: the key as a raw-key literal, `x'<64 hex>'`.
     * Raw, not a passphrase: the key is already 256 random bits, so SQLCipher's passphrase
     * derivation (hundreds of thousands of PBKDF2 rounds on every open) would add nothing but delay.
     * Every open - Room's factory, the export, the checks - must use this same form.
     */
    fun passphrase(key: ByteArray): ByteArray = "x'${hex(key)}'".toByteArray(Charsets.US_ASCII)

    /** Whether [file] is an unencrypted SQLite database (its fixed 16-byte header). */
    fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_HEADER.size) return false
        val head = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { if (it.read(head) != head.size) return false }
        return head.contentEquals(SQLITE_HEADER)
    }

    /**
     * Gets [name] ready for Room with [key]: finishes an interrupted swap, encrypts a plaintext file,
     * and sets aside one this key can't open. [failAfter] is for tests: it throws after that step
     * (1-3) to simulate the process dying there.
     */
    fun prepare(
        context: Context,
        key: ByteArray,
        name: String = DB_NAME,
        onReset: () -> Unit = {},
        failAfter: Int = 0,
        oldKeys: () -> List<ByteArray> = { DbKeyStore.asideKeys(context, ASIDE_KEEP_MS) },
    ) {
        loadLibrary()
        val db = context.getDatabasePath(name)
        recover(db)
        if (isPlaintext(db)) {
            try {
                encrypt(db, key, failAfter)
            } catch (e: SimulatedCrash) {
                throw e
            } catch (e: Exception) {
                // Keep the plaintext and carry on with it this run; the next launch tries again.
                Diag.log("database: encryption failed (${e.javaClass.simpleName}: ${e.message}) - will retry")
                sibling(db, TMP_SUFFIX).delete()
                throw EncryptionPending(e)
            }
            return
        }
        readoptOrCleanAside(db, key, oldKeys)
        // Only a genuine wrong key ("file is not a database") counts as a lost key. Any other
        // failure - I/O, a locked file, low storage - is left alone: Room tries again, and so does
        // the next start, rather than an ordinary hiccup costing the history.
        if (db.exists() && probe(db, key) == Probe.WRONG_KEY) {
            val aside = sibling(db, UNREADABLE_SUFFIX + System.currentTimeMillis())
            // Aged from now (a rename keeps the last write time): it gets the full keep period.
            if (db.renameTo(aside)) aside.setLastModified(System.currentTimeMillis())
            // Its log files go with it: the newest writes live in -wal until a checkpoint, and a
            // copy brought back without it would be missing them.
            moveLogs(db, aside)
            Diag.log("database: couldn't be decrypted (key lost) - started empty; old file kept as ${aside.name}")
            onReset()
        }
    }

    /**
     * Files set aside as unreadable get another chance on every start: one that now opens - with the
     * current key (the key came back, say the settings copy was restored) or with an earlier key
     * still on file ([oldKeys]: a key file the keystore once failed to read) - is moved back in
     * place, as long as the current database holds nothing yet; the empty one goes aside instead.
     * One opened by an earlier key is first re-encrypted to the current one. One that still doesn't
     * open after [ASIDE_KEEP_MS] is deleted.
     */
    private fun readoptOrCleanAside(db: File, key: ByteArray, oldKeys: () -> List<ByteArray>) {
        val dir = db.parentFile ?: return
        val aside = dir.listFiles { f -> f.name.startsWith(db.name + UNREADABLE_SUFFIX) && LOG_SUFFIXES.none { f.name.endsWith(it) } && !f.name.endsWith(TMP_SUFFIX) }
            ?.sortedByDescending { it.lastModified() } ?: return
        if (aside.isEmpty()) return
        val earlier by lazy { oldKeys() }
        val now = System.currentTimeMillis()
        for (f in aside) {
            var state = probe(f, key)
            if (state == Probe.WRONG_KEY) {
                val old = earlier.firstOrNull { probe(f, it) == Probe.OK }
                if (old != null && rekeyInPlace(f, old, key)) state = probe(f, key)
            }
            when (state) {
                Probe.OK -> if (!db.exists() || isEmpty(db, key)) {
                    val empty = sibling(db, "-empty-$now")
                    if (db.exists()) { db.renameTo(empty); moveLogs(db, empty) }
                    if (f.renameTo(db)) {
                        moveLogs(f, db)
                        Diag.log("database: an earlier set-aside copy opens again - restored it")
                        empty.delete(); LOG_SUFFIXES.forEach { sibling(empty, it).delete() }
                        return
                    }
                }
                Probe.WRONG_KEY -> if (now - f.lastModified() > ASIDE_KEEP_MS) {
                    f.delete(); LOG_SUFFIXES.forEach { sibling(f, it).delete() }
                }
                Probe.OTHER -> Unit
            }
        }
    }

    private val LOG_SUFFIXES = listOf("-wal", "-shm", "-journal")

    /** Moves [from]'s log files to sit beside [to] (replacing any there). */
    private fun moveLogs(from: File, to: File) {
        for (suffix in LOG_SUFFIXES) {
            val src = sibling(from, suffix)
            val dst = sibling(to, suffix)
            dst.delete()
            if (src.exists() && !src.renameTo(dst)) src.delete()
        }
    }

    /**
     * Re-encrypts [file] from [oldKey] to [newKey], the same export-verify-swap as the plaintext
     * move ([encrypt]): the copy is checked row for row before it replaces the original.
     */
    private fun rekeyInPlace(file: File, oldKey: ByteArray, newKey: ByteArray): Boolean = try {
        val tmp = sibling(file, TMP_SUFFIX).also { it.delete() }
        val counts: Map<String, Long>
        val src = SQLiteDatabase.openDatabase(file.path, passphrase(oldKey), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null)
        try {
            src.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            counts = rowCounts(src)
            val version = src.version
            src.execSQL("ATTACH DATABASE ? AS rekeyed KEY \"x'${hex(newKey)}'\"", arrayOf(tmp.path))
            src.rawQuery("SELECT sqlcipher_export('rekeyed')", null).use { it.moveToFirst() }
            src.execSQL("PRAGMA rekeyed.user_version = $version")
            src.execSQL("DETACH DATABASE rekeyed")
        } finally {
            src.close()
        }
        SQLiteDatabase.openDatabase(tmp.path, passphrase(newKey), null, SQLiteDatabase.OPEN_READONLY, null, null).use { copy ->
            check(rowCounts(copy) == counts) { "row counts differ after re-encryption" }
        }
        LOG_SUFFIXES.forEach { sibling(file, it).delete() } // folded in by the checkpoint
        check(tmp.renameTo(file)) { "couldn't replace the re-encrypted copy" }
        Diag.log("database: a set-aside copy opened with an earlier key - re-encrypted to the current one")
        true
    } catch (e: Exception) {
        Diag.log("database: re-encrypting a set-aside copy failed (${e.javaClass.simpleName})")
        sibling(file, TMP_SUFFIX).delete()
        false
    }

    internal enum class Probe { OK, WRONG_KEY, OTHER }

    /** Opens [db] with [key] and reads its schema: OK, the wrong key, or some other failure. */
    internal fun probe(db: File, key: ByteArray): Probe = try {
        SQLiteDatabase.openDatabase(db.path, passphrase(key), null, SQLiteDatabase.OPEN_READONLY, null, null).use { d ->
            d.rawQuery("SELECT COUNT(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
        Probe.OK
    } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
        Probe.WRONG_KEY
    } catch (e: android.database.sqlite.SQLiteDatabaseCorruptException) {
        Probe.WRONG_KEY
    } catch (e: Exception) {
        Probe.OTHER
    }

    /** True when [db] has no rows in any of its user tables. */
    private fun isEmpty(db: File, key: ByteArray): Boolean = try {
        SQLiteDatabase.openDatabase(db.path, passphrase(key), null, SQLiteDatabase.OPEN_READONLY, null, null).use { d ->
            rowCounts(d).filterKeys { it != "room_master_table" && it != "android_metadata" }.values.all { it == 0L }
        }
    } catch (e: Exception) {
        false
    }

    /** Thrown when the plaintext file couldn't be encrypted this time; the caller opens it as is. */
    class EncryptionPending(cause: Exception) : Exception(cause)

    /** Test hook: the process "died" after a step. */
    class SimulatedCrash(step: Int) : RuntimeException("simulated crash after step $step")

    /** Finishes a swap a killed process left half done. */
    private fun recover(db: File) {
        val tmp = sibling(db, TMP_SUFFIX)
        val plain = sibling(db, PLAIN_SUFFIX)
        when {
            // Killed after moving the plaintext aside, before moving the verified copy in.
            !db.exists() && plain.exists() && tmp.exists() -> {
                tmp.renameTo(db); plain.delete()
                Diag.log("database: finished an interrupted encryption")
            }
            // Killed with the copy in place but the plaintext not yet deleted.
            db.exists() && !isPlaintext(db) && plain.exists() -> plain.delete()
            // Killed before the swap: the copy may be partial; the plaintext is intact.
            tmp.exists() -> tmp.delete()
        }
        // A plaintext set aside with nothing to replace it (shouldn't happen): put it back.
        if (!db.exists() && plain.exists()) plain.renameTo(db)
    }

    private fun encrypt(db: File, key: ByteArray, failAfter: Int) {
        val tmp = sibling(db, TMP_SUFFIX).also { it.delete() }
        val counts: Map<String, Long>
        // 1. Export, through SQLCipher with an empty key (= plaintext), into an encrypted copy.
        // CREATE_IF_NECESSARY: an ATTACHed file opens with the main database's flags, and the copy
        // doesn't exist yet.
        val src = SQLiteDatabase.openDatabase(
            db.path, "", null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null,
        )
        try {
            // Fold any WAL content into the main file first, so nothing is left behind in -wal.
            src.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            counts = rowCounts(src)
            val version = src.version
            src.execSQL("ATTACH DATABASE ? AS encrypted KEY \"x'${hex(key)}'\"", arrayOf(tmp.path))
            src.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
            src.execSQL("PRAGMA encrypted.user_version = $version")
            src.execSQL("DETACH DATABASE encrypted")
        } finally {
            src.close()
        }
        if (failAfter == 1) throw SimulatedCrash(1)

        // 2. Verify: the copy opens with the key and holds every row.
        val copy = SQLiteDatabase.openDatabase(tmp.path, passphrase(key), null, SQLiteDatabase.OPEN_READONLY, null, null)
        try {
            val copied = rowCounts(copy)
            check(copied == counts) { "row counts differ after export: $counts vs $copied" }
        } finally {
            copy.close()
        }
        if (failAfter == 2) throw SimulatedCrash(2)

        // 3. Swap. The plaintext's -wal/-shm must go before the copy takes its name, or SQLite would
        //    try to apply the plaintext's log to the encrypted file.
        val plain = sibling(db, PLAIN_SUFFIX)
        check(db.renameTo(plain)) { "couldn't move the plaintext aside" }
        listOf("-wal", "-shm", "-journal").forEach { sibling(db, it).delete() }
        if (failAfter == 3) throw SimulatedCrash(3)
        check(tmp.renameTo(db)) { "couldn't move the encrypted copy into place" }
        plain.delete()
        Diag.log("database: encrypted (${counts.values.sum()} rows in ${counts.size} tables)")
    }

    /** Rows per user table (Room's own bookkeeping included; SQLite internals left out). */
    private fun rowCounts(db: SQLiteDatabase): Map<String, Long> {
        val tables = ArrayList<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
            while (c.moveToNext()) tables.add(c.getString(0))
        }
        return tables.associateWith { t ->
            db.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
    }

    private fun sibling(db: File, suffix: String) = File(db.path + suffix)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
