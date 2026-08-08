package com.noti.logger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationEntity::class, RelayedMessageEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class NotiDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun relayedMessageDao(): RelayedMessageDao

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

        fun get(context: Context): NotiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotiDatabase::class.java,
                    "noti.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
        }
    }
}
