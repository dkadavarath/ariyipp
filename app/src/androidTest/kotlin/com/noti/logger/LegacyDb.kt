package com.noti.logger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.noti.logger.data.NotiDatabase

/**
 * Builds a plaintext v13 database file the way an installed v1.43 phone has it: the original v1
 * schema, then every shipped migration up to 13 run over it. [seed] adds rows at v13.
 */
object LegacyDb {

    fun buildV13(ctx: Context, name: String, seed: (SupportSQLiteDatabase) -> Unit) {
        ctx.deleteDatabase(name)
        val path = ctx.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).apply {
            execSQL(
                "CREATE TABLE `notifications` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, " +
                    "`packageName` TEXT NOT NULL, `appLabel` TEXT, `postTime` INTEGER NOT NULL, " +
                    "`title` TEXT, `text` TEXT, `bigText` TEXT, `subText` TEXT, `category` TEXT, " +
                    "`sbnKey` TEXT, `uploaded` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            execSQL("CREATE INDEX `index_notifications_uploaded` ON `notifications` (`uploaded`)")
            execSQL("CREATE INDEX `index_notifications_postTime` ON `notifications` (`postTime`)")
            execSQL("CREATE UNIQUE INDEX `index_notifications_uid` ON `notifications` (`uid`)")
            version = 1
            close()
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        listOf(
                            NotiDatabase.MIGRATION_1_2, NotiDatabase.MIGRATION_2_3, NotiDatabase.MIGRATION_3_4,
                            NotiDatabase.MIGRATION_4_5, NotiDatabase.MIGRATION_5_6, NotiDatabase.MIGRATION_6_7,
                            NotiDatabase.MIGRATION_7_8, NotiDatabase.MIGRATION_8_9, NotiDatabase.MIGRATION_9_10,
                            NotiDatabase.MIGRATION_10_11, NotiDatabase.MIGRATION_11_12, NotiDatabase.MIGRATION_12_13,
                        ).forEach { it.migrate(db) }
                    }
                }).build()
        )
        val db = helper.writableDatabase
        seed(db)
        helper.close()
    }
}
