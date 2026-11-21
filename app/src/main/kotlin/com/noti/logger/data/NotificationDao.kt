package com.noti.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: NotificationEntity): Long

    @Query("SELECT * FROM notifications WHERE uploaded = 0 ORDER BY postTime ASC, id ASC LIMIT :limit")
    suspend fun pendingBatch(limit: Int): List<NotificationEntity>

    /** The next page of pending rows strictly after ([afterPostTime], [afterId]) in upload order -
     *  keyset paging, so a page whose rows the endpoint rejected can be stepped past within a run
     *  instead of being re-fetched (and blocking everything newer) forever. */
    @Query(
        "SELECT * FROM notifications WHERE uploaded = 0 AND " +
            "(postTime > :afterPostTime OR (postTime = :afterPostTime AND id > :afterId)) " +
            "ORDER BY postTime ASC, id ASC LIMIT :limit"
    )
    suspend fun pendingBatchAfter(afterPostTime: Long, afterId: Long, limit: Int): List<NotificationEntity>

    @Query("UPDATE notifications SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("DELETE FROM notifications WHERE uploaded = 1 AND postTime < :cutoff")
    suspend fun purgeUploadedOlderThan(cutoff: Long): Int

    /** Local-only retention (no webhook): rows are never marked uploaded, so they're pruned by
     *  age alone. */
    @Query("DELETE FROM notifications WHERE postTime < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    /** Drops the [count] oldest rows - local-only mode's answer to the row cap, so capture keeps
     *  going (newest wins) instead of stopping for good. */
    @Query("DELETE FROM notifications WHERE id IN (SELECT id FROM notifications ORDER BY postTime ASC LIMIT :count)")
    suspend fun trimOldest(count: Int): Int

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    /** Count rows with the same content captured at/after [since] (epoch ms) - for dedup. */
    @Query("SELECT COUNT(*) FROM notifications WHERE contentHash = :hash AND createdAt >= :since")
    suspend fun countRecentByHash(hash: String, since: Long): Int

    /** Count rows from a single package captured at/after [since] - a per-package rate-limit
     *  backstop against a single noisy/malicious source flooding storage with unique content that
     *  the content-hash dedup above can't catch. */
    @Query("SELECT COUNT(*) FROM notifications WHERE packageName = :packageName AND createdAt >= :since")
    suspend fun countRecentByPackage(packageName: String, since: Long): Int

    /** Captured notifications from one package since [since], for the Reminder rulebook scan.
     *  Not suspend: the scan runs on a worker thread and walks several packages in a loop, where a
     *  plain blocking call is simpler than threading coroutines through it. */
    @Query("SELECT * FROM notifications WHERE packageName = :packageName AND postTime >= :since ORDER BY postTime ASC")
    fun forPackageSince(packageName: String, since: Long): List<NotificationEntity>

    /** Highest row id stored, or null for an empty table - the ceiling an incremental Reminder scan
     *  reads up to, so a row written mid-scan is left whole for the next one. */
    @Query("SELECT MAX(id) FROM notifications")
    fun maxId(): Long?

    /** One package's notifications with row id in (afterId, upTo], for the incremental Reminder scan.
     *  By id rather than [NotificationEntity.postTime] because a scan now runs on every eligible
     *  arrival: a notification posted just before a scan started but written just after it would
     *  fall behind a time watermark and never be read. [notBefore] keeps the lookback floor. */
    @Query("SELECT * FROM notifications WHERE packageName = :packageName AND id > :afterId AND id <= :upTo AND postTime >= :notBefore ORDER BY postTime ASC")
    fun forPackageInIds(packageName: String, afterId: Long, upTo: Long, notBefore: Long): List<NotificationEntity>
}
