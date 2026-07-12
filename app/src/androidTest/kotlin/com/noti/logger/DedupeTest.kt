package com.noti.logger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.NotificationDao
import com.noti.logger.data.NotificationEntity
import com.noti.logger.util.contentHash
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DedupeTest {

    private lateinit var db: NotiDatabase
    private lateinit var dao: NotificationDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, NotiDatabase::class.java).build()
        dao = db.notificationDao()
    }

    @After fun tearDown() = db.close()

    private fun entity(uid: String, hash: String, createdAt: Long) = NotificationEntity(
        uid = uid, packageName = "com.app", appLabel = "App", postTime = createdAt,
        title = "T", text = "B", bigText = null, subText = null, category = "msg",
        sbnKey = "k-$uid", contentHash = hash, uploaded = 0, createdAt = createdAt
    )

    @Test
    fun countRecentByHash_respects_window() = runBlocking {
        val hash = contentHash("com.app", "T", "B", null, null)
        val now = System.currentTimeMillis()
        // one row 10s ago, one row 2 hours ago
        dao.insert(entity("a", hash, now - 10_000))
        dao.insert(entity("b", hash, now - 2 * 3_600_000))

        // 60s window sees only the recent one
        assertEquals(1, dao.countRecentByHash(hash, now - 60_000))
        // 3h window sees both
        assertEquals(2, dao.countRecentByHash(hash, now - 3 * 3_600_000))
        // different hash sees none
        assertEquals(0, dao.countRecentByHash("deadbeef", now - 3 * 3_600_000))
    }

    @Test
    fun simulated_dedup_flow_drops_duplicate_within_window() = runBlocking {
        val hash = contentHash("com.app", "T", "B", null, null)
        val now = System.currentTimeMillis()
        val windowMs = 86_400_000L // 1 day

        // First capture: no recent duplicate → insert.
        assertEquals(0, dao.countRecentByHash(hash, now - windowMs))
        dao.insert(entity("first", hash, now))

        // Second identical capture within window → detected, would be dropped.
        assertEquals(1, dao.countRecentByHash(hash, now - windowMs))
        assertEquals(1, dao.totalCount())
    }
}
