package com.noti.logger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageDao
import com.noti.logger.data.RelayedMessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayedMessageDaoTest {

    private lateinit var db: NotiDatabase
    private lateinit var dao: RelayedMessageDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), NotiDatabase::class.java
        ).build()
        dao = db.relayedMessageDao()
    }

    @After
    fun teardown() = db.close()

    private fun msg(sender: String, body: String, at: Long) =
        RelayedMessageEntity(sender = sender, sim = "e&", body = body, receivedAt = at)

    @Test
    fun conversations_group_by_sender_latest_first() {
        dao.insert(msg("+111", "hi", 100))
        dao.insert(msg("+111", "again", 300))
        dao.insert(msg("+222", "yo", 200))

        val convos = dao.conversations()
        assertEquals(2, convos.size)
        assertEquals("+111", convos[0].sender)   // most recent (300) first
        assertEquals("again", convos[0].lastBody) // latest body in the conversation
        assertEquals(2, convos[0].count)
        assertEquals("+222", convos[1].sender)
    }

    @Test
    fun messagesFor_returns_oldest_first() {
        dao.insert(msg("+111", "b", 200))
        dao.insert(msg("+111", "a", 100))
        assertEquals(listOf("a", "b"), dao.messagesFor("+111").map { it.body })
    }

    @Test
    fun search_matches_sender_or_body() {
        dao.insert(msg("+111", "hello world", 100))
        dao.insert(msg("+222", "otp 1234", 200))

        assertEquals(listOf("+222"), dao.searchConversations("otp").map { it.sender })
        assertEquals(listOf("+111"), dao.searchConversations("world").map { it.sender })
        assertEquals(listOf("+111"), dao.searchConversations("111").map { it.sender })
    }

    @Test
    fun delete_conversation_removes_only_that_sender() {
        dao.insert(msg("+111", "a", 100))
        dao.insert(msg("+222", "b", 200))
        dao.deleteConversation("+111")
        assertEquals(listOf("+222"), dao.conversations().map { it.sender })
    }

    @Test
    fun delete_message_removes_only_that_row() {
        val keep = dao.insert(msg("+111", "keep", 100))
        val drop = dao.insert(msg("+111", "drop", 200))
        dao.deleteMessage(drop)
        assertEquals(listOf("keep"), dao.messagesFor("+111").map { it.body })
        assertEquals(keep, dao.messagesFor("+111").single().id)
    }

    @Test
    fun unread_counts_only_incoming_unread_and_markRead_clears_them() {
        dao.insert(msg("+111", "in-a", 100))                                   // unread
        dao.insert(msg("+111", "in-b", 200))                                   // unread
        dao.insert(RelayedMessageEntity(sender = "+111", sim = "", body = "mine", receivedAt = 300, outgoing = 1)) // outgoing, never counts
        dao.insert(msg("+222", "other", 150))                                  // unread, different convo

        val before = dao.conversations().associateBy { it.sender }
        assertEquals(2, before["+111"]!!.unread)
        assertEquals(1, before["+222"]!!.unread)
        assertEquals(3, dao.totalUnread())

        assertEquals("two incoming rows marked", 2, dao.markRead("+111"))
        val after = dao.conversations().associateBy { it.sender }
        assertEquals(0, after["+111"]!!.unread)
        assertEquals(1, after["+222"]!!.unread)   // untouched
        assertEquals(1, dao.totalUnread())
        assertEquals("marking again is a no-op", 0, dao.markRead("+111"))
    }

    // ---- Paging (ChatActivity's newest-page + scroll-up history) ----

    private fun insertConversation(sender: String, count: Int, startAt: Long): List<Long> =
        (0 until count).map { i -> dao.insert(msg(sender, "m$i", startAt + i)) }

    @Test
    fun messagesPage_returns_the_newest_window_oldest_first() {
        insertConversation("+111", 10, 100)
        val page = dao.messagesPage("+111", limit = 4, offset = 0)
        assertEquals(4, page.size)
        // Newest four (m6..m9), but still in chronological order for the chat timeline.
        assertEquals(listOf("m6", "m7", "m8", "m9"), page.map { it.body })
    }

    @Test
    fun messagesPage_walks_back_older_pages_with_offset() {
        insertConversation("+111", 10, 100)
        val older = dao.messagesPage("+111", limit = 4, offset = 4)
        assertEquals(listOf("m2", "m3", "m4", "m5"), older.map { it.body })

        val oldest = dao.messagesPage("+111", limit = 4, offset = 8)
        assertEquals("a short trailing page means 'all loaded'", listOf("m0", "m1"), oldest.map { it.body })

        assertTrue("offset past the start is empty", dao.messagesPage("+111", limit = 4, offset = 12).isEmpty())
    }

    @Test
    fun messagesPage_scopes_strictly_to_one_sender() {
        insertConversation("+111", 5, 100)
        insertConversation("+222", 3, 500)
        assertEquals(3, dao.messagesPage("+222", limit = 10, offset = 0).size)
        assertEquals("+222", dao.messagesPage("+222", limit = 10, offset = 0).first().sender)
    }

    @Test
    fun purgeOlderThan_deletes_only_rows_before_the_cutoff() {
        val now = System.currentTimeMillis()
        dao.insert(msg("+old", "ancient", now - 40L * 86_400_000))
        dao.insert(msg("+edge", "yesterday", now - 86_400_000))
        dao.insert(msg("+new", "fresh", now - 60_000))

        val deleted = dao.purgeOlderThan(now - 30L * 86_400_000)

        assertEquals(1, deleted)
        assertTrue("40-day-old row is gone", dao.messagesFor("+old").isEmpty())
        assertEquals(2, dao.conversations().size)
    }

    @Test
    fun conversationsFlow_emits_the_current_aggregate() = runBlocking {
        dao.insert(msg("+111", "hi", 100))
        dao.insert(msg("+222", "yo", 200))

        val convos = dao.conversationsFlow().first()

        assertEquals(listOf("+222", "+111"), convos.map { it.sender })
        assertEquals(1, convos[0].count)
    }

    @Test
    fun changeToken_reacts_to_inserts_deletes_and_status_updates_but_not_markRead() = runBlocking {
        val initial = dao.changeToken().first()
        assertEquals(0, initial.rows)

        val id = dao.insert(msg("+111", "hi", 100))
        val afterInsert = dao.changeToken().first()
        assertEquals(1, afterInsert.rows)
        assertEquals(id, afterInsert.maxId)

        // A delivery-status update must move the token even though the row count doesn't change -
        // this is what drives live tick updates in an open chat.
        dao.updateStatus(id, com.noti.shared.WireMessage.DeliveryAck.SMS_DELIVERED)
        val afterAck = dao.changeToken().first()
        assertEquals(afterInsert.maxStatus + 3, afterAck.maxStatus)
        assertTrue(afterAck != afterInsert)

        // Marking read changes neither rows, maxStatus, nor maxId - the token must stay put so an
        // open chat doesn't reload itself in a loop after its own markRead.
        dao.markRead("+111")
        assertEquals(afterAck, dao.changeToken().first())

        dao.deleteMessage(id)
        assertTrue(dao.changeToken().first() != afterAck)
    }
}
