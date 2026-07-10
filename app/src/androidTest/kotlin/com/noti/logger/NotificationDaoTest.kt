package com.noti.logger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.NotificationDao
import com.noti.logger.data.NotificationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationDaoTest {

    private lateinit var db: NotiDatabase
    private lateinit var dao: NotificationDao

    private fun entity(uid: String, postTime: Long, uploaded: Int = 0) = NotificationEntity(
        uid = uid,
        packageName = "com.example.app",
        appLabel = "Example",
        postTime = postTime,
        title = "t",
        text = "b",
        bigText = null,
        subText = null,
        category = "msg",
        sbnKey = "k-$uid",
        uploaded = uploaded,
        createdAt = postTime
    )

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, NotiDatabase::class.java).build()
        dao = db.notificationDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insert_and_counts() = runBlocking {
        dao.insert(entity("a", 100))
        dao.insert(entity("b", 200))
        assertEquals(2, dao.totalCount())
        assertEquals(2, dao.pendingCount())
    }

    @Test
    fun duplicate_uid_is_ignored() = runBlocking {
        val first = dao.insert(entity("dup", 100))
        val second = dao.insert(entity("dup", 999)) // same uid -> IGNORE
        assertEquals(1, dao.totalCount())
        assert(first != -1L)
        assertEquals(-1L, second) // OnConflict.IGNORE returns -1 for the rejected row
    }

    @Test
    fun pendingBatch_orders_by_postTime_and_respects_limit() = runBlocking {
        dao.insert(entity("c", 300))
        dao.insert(entity("a", 100))
        dao.insert(entity("b", 200))
        val batch = dao.pendingBatch(2)
        assertEquals(2, batch.size)
        assertEquals("a", batch[0].uid) // oldest first
        assertEquals("b", batch[1].uid)
    }

    @Test
    fun markUploaded_moves_rows_out_of_pending() = runBlocking {
        val id1 = dao.insert(entity("a", 100))
        dao.insert(entity("b", 200))
        dao.markUploaded(listOf(id1))
        assertEquals(1, dao.pendingCount())
        assertEquals(2, dao.totalCount())
    }

    @Test
    fun purge_only_deletes_uploaded_rows_older_than_cutoff() = runBlocking {
        dao.insert(entity("old-uploaded", 100, uploaded = 1))
        dao.insert(entity("new-uploaded", 5_000, uploaded = 1))
        dao.insert(entity("old-pending", 100, uploaded = 0))
        val deleted = dao.purgeUploadedOlderThan(1_000)
        assertEquals(1, deleted) // only old-uploaded
        assertEquals(2, dao.totalCount())
    }
}
