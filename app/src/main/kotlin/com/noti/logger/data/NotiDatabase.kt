package com.noti.logger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NotificationEntity::class], version = 1, exportSchema = false)
abstract class NotiDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile
        private var INSTANCE: NotiDatabase? = null

        fun get(context: Context): NotiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotiDatabase::class.java,
                    "noti.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
