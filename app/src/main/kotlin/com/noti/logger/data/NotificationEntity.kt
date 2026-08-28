package com.noti.logger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["uploaded"]),
        Index(value = ["postTime"]),
        Index(value = ["uid"], unique = true),
        Index(value = ["contentHash"]),
        Index(value = ["packageName", "createdAt"])
    ]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val packageName: String,
    val appLabel: String?,
    val postTime: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val category: String?,
    val sbnKey: String?,
    /** SHA-256 of the raw content; used for time-windowed duplicate detection.
     *  defaultValue matches the v1→v2 migration's ADD COLUMN default so Room schema validation passes. */
    @ColumnInfo(defaultValue = "")
    val contentHash: String = "",
    val uploaded: Int = 0,
    val createdAt: Long
)
