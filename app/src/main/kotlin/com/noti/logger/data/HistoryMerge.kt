package com.noti.logger.data

import android.content.ContentValues
import android.database.Cursor
import com.noti.logger.reminder.BillStatus
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Merges the reminders of a recovered database (attached as `old`) into the current one (`main`),
 * row by row, inside the caller's transaction (see DbEncryption.mergeOld). Neither side is thrown
 * away: the current database is what the user has been using since the reset, the old one holds
 * the rulebook and the paid history from before it.
 *
 * - Rules match by name and type. An old rule the current database already has maps onto it;
 *   one it lacks is added.
 * - Bills go under their rule as mapped. An old bill already present - same source, or same rule,
 *   due date and amount (a manual one: same name and due date) - isn't added twice; and if the old
 *   one was settled (paid, skipped) while the current one is still outstanding, the current one is
 *   marked settled the same way. That is what stops a statement re-read after the reset from
 *   coming back unpaid. The rest are added.
 * - Parking sessions are added unless one with the same name, zone and start is there.
 */
internal object HistoryMerge {

    fun mergeReminders(d: SQLiteDatabase) {
        val ruleMap = mergeRules(d)
        mergeBills(d, ruleMap)
        mergeParking(d)
    }

    private fun mergeRules(d: SQLiteDatabase): Map<Long, Long> {
        val current = HashMap<String, Long>()
        d.rawQuery("SELECT id, name, type FROM main.reminder_rules", null).use { c ->
            while (c.moveToNext()) current.putIfAbsent(c.getString(1) + "\u0000" + c.getString(2), c.getLong(0))
        }
        val map = HashMap<Long, Long>()
        val cols = columns(d, "reminder_rules")
        d.rawQuery("SELECT * FROM old.reminder_rules", null).use { c ->
            while (c.moveToNext()) {
                val oldId = c.getLong(c.getColumnIndexOrThrow("id"))
                val key = c.getString(c.getColumnIndexOrThrow("name")) + "\u0000" + c.getString(c.getColumnIndexOrThrow("type"))
                map[oldId] = current[key] ?: d.insertOrThrow("main.reminder_rules", null, values(c, cols)).also { current[key] = it }
            }
        }
        return map
    }

    private fun mergeBills(d: SQLiteDatabase, ruleMap: Map<Long, Long>) {
        val cols = columns(d, "reminder_bills")
        d.rawQuery("SELECT * FROM old.reminder_bills", null).use { c ->
            while (c.moveToNext()) mergeBill(d, c, cols, ruleMap)
        }
    }

    /** One old bill: added under its mapped rule, or folded into the current bill it already is. */
    private fun mergeBill(d: SQLiteDatabase, c: Cursor, cols: Set<String>, ruleMap: Map<Long, Long>) {
        val oldRule = c.getLong(c.getColumnIndexOrThrow("ruleId"))
        val ruleId = if (oldRule > 0) ruleMap[oldRule] ?: 0L else 0L
        val match = findBill(d, c, ruleId)
        if (match == null) {
            d.insertOrThrow("main.reminder_bills", null, values(c, cols).apply { put("ruleId", ruleId) })
            return
        }
        // Settled wins: a bill paid or skipped before the reset stays so after it.
        val oldSettled = c.getInt(c.getColumnIndexOrThrow("status")) != BillStatus.ACTIVE
        if (match.second != BillStatus.ACTIVE || !oldSettled) return
        val settled = ContentValues()
        for (k in listOf("status", "settledAt", "settleKey", "autoSettled")) if (k in cols) putFrom(c, k, settled)
        d.update("main.reminder_bills", settled, "id = ?", arrayOf(match.first.toString()))
    }

    /** The current bill the old one at [c] already is: (id, status), or null. */
    private fun findBill(d: SQLiteDatabase, c: Cursor, ruleId: Long): Pair<Long, Int>? {
        val sourceKey = c.getString(c.getColumnIndexOrThrow("sourceKey")).orEmpty()
        val dueAt = c.getLong(c.getColumnIndexOrThrow("dueAt"))
        val (sql, args) = when {
            ruleId > 0 -> {
                val amountIdx = c.getColumnIndexOrThrow("amount")
                if (c.isNull(amountIdx)) {
                    "SELECT id, status FROM main.reminder_bills WHERE sourceKey = ? OR (ruleId = ? AND dueAt = ? AND amount IS NULL)" to
                        arrayOf(sourceKey, ruleId.toString(), dueAt.toString())
                } else {
                    "SELECT id, status FROM main.reminder_bills WHERE sourceKey = ? OR (ruleId = ? AND dueAt = ? AND abs(amount - ?) < 0.005)" to
                        arrayOf(sourceKey, ruleId.toString(), dueAt.toString(), c.getDouble(amountIdx).toString())
                }
            }
            else -> "SELECT id, status FROM main.reminder_bills WHERE sourceKey = ? OR (ruleId = 0 AND name = ? AND dueAt = ?)" to
                arrayOf(sourceKey, c.getString(c.getColumnIndexOrThrow("name")), dueAt.toString())
        }
        return d.rawQuery(sql, args).use { r -> if (r.moveToFirst()) r.getLong(0) to r.getInt(1) else null }
    }

    private fun mergeParking(d: SQLiteDatabase) {
        val cols = columns(d, "reminder_parking")
        d.rawQuery("SELECT * FROM old.reminder_parking", null).use { c ->
            while (c.moveToNext()) {
                val exists = d.rawQuery(
                    "SELECT 1 FROM main.reminder_parking WHERE name = ? AND zone = ? AND startedAt = ?",
                    arrayOf(
                        c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("zone")),
                        c.getLong(c.getColumnIndexOrThrow("startedAt")).toString(),
                    ),
                ).use { it.moveToFirst() }
                if (!exists) d.insertOrThrow("main.reminder_parking", null, values(c, cols))
            }
        }
    }

    /** The current table's columns that the old one has too, without the row id. */
    private fun columns(d: SQLiteDatabase, table: String): Set<String> {
        fun cols(schema: String) = d.rawQuery("PRAGMA $schema.table_info(\"$table\")", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        return cols("main").intersect(cols("old")) - "id"
    }

    private fun values(c: Cursor, cols: Set<String>) = ContentValues().apply { for (k in cols) putFrom(c, k, this) }

    private fun putFrom(c: Cursor, col: String, out: ContentValues) {
        val i = c.getColumnIndex(col)
        if (i < 0) return
        when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> out.putNull(col)
            Cursor.FIELD_TYPE_INTEGER -> out.put(col, c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> out.put(col, c.getDouble(i))
            Cursor.FIELD_TYPE_BLOB -> out.put(col, c.getBlob(i))
            else -> out.put(col, c.getString(i))
        }
    }
}
