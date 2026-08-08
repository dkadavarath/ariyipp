package com.noti.logger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageDao
import com.noti.logger.data.RelayedMessageEntity
import org.junit.After
import org.junit.Assert.assertEquals
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
}
