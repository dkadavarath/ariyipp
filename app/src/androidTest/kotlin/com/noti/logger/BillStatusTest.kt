package com.noti.logger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.NotiDatabase
import com.noti.logger.reminder.BillStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillStatusTest {
    private lateinit var db: NotiDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NotiDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun teardown() = db.close()

    @Test fun marking_a_settled_bill_paid_leaves_how_it_was_settled_alone() {
        val dao = db.billOccurrenceDao()
        val id = dao.insert(BillOccurrenceEntity(name = "card", dueAt = 1, sourceKey = "k"))
        assertEquals(1, dao.settleAutomatically(id, 1_000L, "pay-1"))
        assertEquals("already settled: untouched", 0, dao.setStatus(id, BillStatus.PAID, 9_000L))
        val bill = dao.byId(id)!!
        assertEquals(1_000L, bill.settledAt)
        assertEquals(1, bill.autoSettled)
        val open = dao.insert(BillOccurrenceEntity(name = "rent", dueAt = 1, sourceKey = "k2"))
        assertEquals(1, dao.setStatus(open, BillStatus.PAID, 5_000L))
    }
}
