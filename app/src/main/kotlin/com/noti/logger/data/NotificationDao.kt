package com.noti.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: NotificationEntity): Long

    @Query("SELECT * FROM notifications WHERE uploaded = 0 ORDER BY postTime ASC LIMIT :limit")
    suspend fun pendingBatch(limit: Int): List<NotificationEntity>

    @Query("UPDATE notifications SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("DELETE FROM notifications WHERE uploaded = 1 AND postTime < :cutoff")
    suspend fun purgeUploadedOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    /** Count rows with the same content captured at/after [since] (epoch ms) - for dedup. */
    @Query("SELECT COUNT(*) FROM notifications WHERE contentHash = :hash AND createdAt >= :since")
    suspend fun countRecentByHash(hash: String, since: Long): Int
}
