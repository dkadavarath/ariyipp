package com.noti.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RelayedMessageDao {

    @Insert
    fun insert(message: RelayedMessageEntity): Long

    /** One row per conversation: sender, its latest message body/time, and message count. */
    @Query(
        """
        SELECT m.sender AS sender,
               (SELECT body FROM relayed_messages i WHERE i.sender = m.sender ORDER BY receivedAt DESC, id DESC LIMIT 1) AS lastBody,
               MAX(m.receivedAt) AS lastAt,
               COUNT(*) AS count
        FROM relayed_messages m
        GROUP BY m.sender
        ORDER BY lastAt DESC
        """
    )
    fun conversations(): List<ConversationSummary>

    /** Conversations whose sender or any message body matches [q] (case-insensitive LIKE). */
    @Query(
        """
        SELECT m.sender AS sender,
               (SELECT body FROM relayed_messages i WHERE i.sender = m.sender ORDER BY receivedAt DESC, id DESC LIMIT 1) AS lastBody,
               MAX(m.receivedAt) AS lastAt,
               COUNT(*) AS count
        FROM relayed_messages m
        WHERE m.sender LIKE '%' || :q || '%'
           OR EXISTS (SELECT 1 FROM relayed_messages b WHERE b.sender = m.sender AND b.body LIKE '%' || :q || '%')
        GROUP BY m.sender
        ORDER BY lastAt DESC
        """
    )
    fun searchConversations(q: String): List<ConversationSummary>

    /** All messages in a conversation, oldest first. */
    @Query("SELECT * FROM relayed_messages WHERE sender = :sender ORDER BY receivedAt ASC, id ASC")
    fun messagesFor(sender: String): List<RelayedMessageEntity>

    @Query("DELETE FROM relayed_messages WHERE sender = :sender")
    fun deleteConversation(sender: String)
}
