package com.noti.logger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.NotiDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the v1→v2 migration adds contentHash (+ index) and preserves existing outbox rows,
 * by hand-building a v1 database matching Room's generated v1 schema, then opening with Room.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before fun clean() { ctx.deleteDatabase(dbName) }
    @After fun cleanup() { ctx.deleteDatabase(dbName) }

    @Test
    fun migrate_1_to_2_preserves_rows_and_adds_contentHash() {
        // ---- Build a v1 database exactly as Room generated it ----
        val path = ctx.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val v1 = SQLiteDatabase.openOrCreateDatabase(path, null)
        v1.execSQL(
            "CREATE TABLE `notifications` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, " +
                "`packageName` TEXT NOT NULL, `appLabel` TEXT, `postTime` INTEGER NOT NULL, " +
                "`title` TEXT, `text` TEXT, `bigText` TEXT, `subText` TEXT, `category` TEXT, " +
                "`sbnKey` TEXT, `uploaded` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        v1.execSQL("CREATE INDEX `index_notifications_uploaded` ON `notifications` (`uploaded`)")
        v1.execSQL("CREATE INDEX `index_notifications_postTime` ON `notifications` (`postTime`)")
        v1.execSQL("CREATE UNIQUE INDEX `index_notifications_uid` ON `notifications` (`uid`)")
        v1.execSQL(
            "INSERT INTO notifications " +
                "(uid,packageName,appLabel,postTime,title,text,bigText,subText,category,sbnKey,uploaded,createdAt) " +
                "VALUES ('u1','com.app','App',100,'T','B',NULL,NULL,'msg','k',0,100)"
        )
        v1.version = 1
        v1.close()

        // ---- Open with Room + migrations to the current version; Room validates the schema ----
        val db = Room.databaseBuilder(ctx, NotiDatabase::class.java, dbName)
            .addMigrations(NotiDatabase.MIGRATION_1_2, NotiDatabase.MIGRATION_2_3)
            .build()
        runBlocking {
            val dao = db.notificationDao()
            assertEquals("existing row preserved", 1, dao.totalCount())
            // Migrated row got contentHash '' (the ADD COLUMN default) and createdAt 100.
            assertEquals(1, dao.countRecentByHash("", 0))
            // v2→v3 created the relayed_messages table (querying it must not throw).
            assertEquals(0, db.relayedMessageDao().conversations().size)
        }
        db.close()
    }
}
