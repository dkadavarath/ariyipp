package com.noti.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReminderRuleDao {

    @Query("SELECT * FROM reminder_rules ORDER BY name COLLATE NOCASE")
    fun all(): List<ReminderRuleEntity>

    @Query("SELECT * FROM reminder_rules WHERE enabled = 1")
    fun enabled(): List<ReminderRuleEntity>

    @Query("SELECT * FROM reminder_rules WHERE id = :id")
    fun byId(id: Long): ReminderRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rule: ReminderRuleEntity): Long

    @Update
    fun update(rule: ReminderRuleEntity)

    @Query("DELETE FROM reminder_rules WHERE id = :id")
    fun delete(id: Long)

    @Query("UPDATE reminder_rules SET skipNext = :skip WHERE id = :id")
    fun setSkipNext(id: Long, skip: Int)

    /** Consumes a pending "skip next": 1 for the one caller that gets it, 0 for everyone after.
     *  A plain read-then-clear let every bill in a burst (several statements in one scan) see the
     *  flag still set, so "skip next" skipped them all. */
    @Query("UPDATE reminder_rules SET skipNext = 0 WHERE id = :id AND skipNext = 1")
    fun claimSkipNext(id: Long): Int

    /** For restore, which replaces the rulebook wholesale rather than merging into it. */
    @Query("DELETE FROM reminder_rules")
    fun deleteAll()
}

@Dao
interface BillOccurrenceDao {

    /** Outstanding bills, soonest first - what the tab highlights. */
    @Query("SELECT * FROM reminder_bills WHERE status = 0 ORDER BY CASE WHEN dueAt = 0 THEN 1 ELSE 0 END, dueAt")
    fun active(): List<BillOccurrenceEntity>

    /** Everything settled, newest first - the history list. */
    @Query("SELECT * FROM reminder_bills WHERE status != 0 ORDER BY settledAt DESC, dueAt DESC LIMIT :limit")
    fun history(limit: Int = 200): List<BillOccurrenceEntity>

    /** The most recently settled bill for one rule, for the greyed-out line shown when that rule has
     *  nothing outstanding. */
    @Query("SELECT * FROM reminder_bills WHERE ruleId = :ruleId AND status != 0 ORDER BY settledAt DESC, dueAt DESC LIMIT 1")
    fun lastSettledForRule(ruleId: Long): BillOccurrenceEntity?

    @Query("SELECT COUNT(*) FROM reminder_bills WHERE ruleId = :ruleId AND status = 0")
    fun activeCountForRule(ruleId: Long): Int

    @Query("SELECT * FROM reminder_bills WHERE id = :id")
    fun byId(id: Long): BillOccurrenceEntity?

    @Query("SELECT * FROM reminder_bills WHERE sourceKey = :key LIMIT 1")
    fun bySourceKey(key: String): BillOccurrenceEntity?

    /** A rule's bills due on [dueAt], whatever their status. */
    @Query("SELECT * FROM reminder_bills WHERE ruleId = :ruleId AND dueAt = :dueAt ORDER BY id")
    fun forRuleAndDue(ruleId: Long, dueAt: Long): List<BillOccurrenceEntity>

    /** Active bills whose reminder is due to be (re)scheduled. */
    @Query("SELECT * FROM reminder_bills WHERE status = 0 AND dueAt > 0 ORDER BY dueAt")
    fun schedulable(): List<BillOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(bill: BillOccurrenceEntity): Long

    @Update
    fun update(bill: BillOccurrenceEntity)

    /** Settles an outstanding bill by hand (paid or skipped). A bill already settled - by hand, or
     *  automatically by a payment - is left as it is: re-marking used to overwrite when and how it
     *  was paid. Returns rows changed (0 = it was already settled). */
    @Query("UPDATE reminder_bills SET status = :status, settledAt = :at WHERE id = :id AND status = 0")
    fun setStatus(id: Long, status: Int, at: Long): Int

    /** Outstanding bills of one rule - the candidates a payment message could settle. */
    @Query("SELECT * FROM reminder_bills WHERE ruleId = :ruleId AND status = 0")
    fun activeForRule(ruleId: Long): List<BillOccurrenceEntity>

    /** Has this payment message already settled a bill? Keeps one payment to one bill. */
    @Query("SELECT COUNT(*) FROM reminder_bills WHERE settleKey = :key")
    fun countBySettleKey(key: String): Int

    /** Marks a bill paid by the payment resolver. Guarded on status so a bill the user settled (or
     *  skipped) in the meantime is left as they set it. */
    @Query("UPDATE reminder_bills SET status = 1, settledAt = :at, settleKey = :key, autoSettled = 1 WHERE id = :id AND status = 0")
    fun settleAutomatically(id: Long, at: Long, key: String): Int

    @Query("UPDATE reminder_bills SET remindAt = :remindAt WHERE id = :id")
    fun setRemindAt(id: Long, remindAt: Long)

    @Query("UPDATE reminder_bills SET notified = 1 WHERE id = :id")
    fun markNotified(id: Long)

    @Query("DELETE FROM reminder_bills WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM reminder_bills WHERE status != 0 AND settledAt < :cutoff")
    fun purgeHistoryBefore(cutoff: Long)

    @Query("DELETE FROM reminder_bills")
    fun deleteAll()
}

@Dao
interface ParkingSessionDao {

    @Query("SELECT * FROM reminder_parking WHERE active = 1 ORDER BY CASE WHEN expiresAt = 0 THEN 0 ELSE 1 END, expiresAt")
    fun active(): List<ParkingSessionEntity>

    @Query("SELECT * FROM reminder_parking WHERE active = 0 ORDER BY endedAt DESC LIMIT :limit")
    fun history(limit: Int = 100): List<ParkingSessionEntity>

    @Query("SELECT * FROM reminder_parking WHERE id = :id")
    fun byId(id: Long): ParkingSessionEntity?

    /** The running session a renewal should extend: same rule, same zone. A blank zone matches the
     *  rule's single running session, which is what an unreadable-zone renewal has to fall back on. */
    @Query("SELECT * FROM reminder_parking WHERE active = 1 AND ruleId = :ruleId AND zone = :zone LIMIT 1")
    fun activeForZone(ruleId: Long, zone: String): ParkingSessionEntity?

    @Query("SELECT * FROM reminder_parking WHERE active = 1 AND ruleId = :ruleId ORDER BY startedAt DESC LIMIT 1")
    fun latestActiveForRule(ruleId: Long): ParkingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(session: ParkingSessionEntity): Long

    @Update
    fun update(session: ParkingSessionEntity)

    /** Records that the alarm for reminder time [remindAt] has rung. */
    @Query("UPDATE reminder_parking SET rangFor = :remindAt WHERE id = :id")
    fun markRang(id: Long, remindAt: Long)

    @Query("UPDATE reminder_parking SET active = 0, endedAt = :at WHERE id = :id")
    fun end(id: Long, at: Long)

    @Query("DELETE FROM reminder_parking WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM reminder_parking")
    fun deleteAll()
}
