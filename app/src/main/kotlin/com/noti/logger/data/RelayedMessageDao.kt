package com.noti.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RelayedMessageDao {

    @Insert
    fun insert(message: RelayedMessageEntity): Long

    /** One row per conversation: sender, its latest message body/time, total count, and unread count. */
    @Query(
        """
        SELECT m.sender AS sender,
               (SELECT body FROM relayed_messages i WHERE i.sender = m.sender ORDER BY receivedAt DESC, id DESC LIMIT 1) AS lastBody,
               MAX(m.receivedAt) AS lastAt,
               COUNT(*) AS count,
               SUM(CASE WHEN m.outgoing = 0 AND m.read = 0 THEN 1 ELSE 0 END) AS unread
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
               COUNT(*) AS count,
               SUM(CASE WHEN m.outgoing = 0 AND m.read = 0 THEN 1 ELSE 0 END) AS unread
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

    @Query("DELETE FROM relayed_messages WHERE id = :id")
    fun deleteMessage(id: Long)

    /** Deletes all received (inbound) messages, keeping ones composed here. Returns rows removed.
     *  Used before a full repush from ariy so the rebuilt history has no pre-hash duplicates. */
    @Query("DELETE FROM relayed_messages WHERE outgoing = 0")
    fun deleteInbound(): Int

    /** Marks every incoming message in a conversation as read. Returns rows changed. */
    @Query("UPDATE relayed_messages SET read = 1 WHERE sender = :sender AND outgoing = 0 AND read = 0")
    fun markRead(sender: String): Int

    /** Total unread incoming messages across all conversations (for the tab badge). */
    @Query("SELECT COUNT(*) FROM relayed_messages WHERE outgoing = 0 AND read = 0")
    fun totalUnread(): Int

    /** How many stored messages carry this content-dedupe key (for missed-SMS dedup). */
    @Query("SELECT COUNT(*) FROM relayed_messages WHERE dedupe = :key")
    fun countByDedupe(key: String): Int

    /** Every stored message (for export/backup). */
    @Query("SELECT * FROM relayed_messages ORDER BY receivedAt ASC, id ASC")
    fun allMessages(): List<RelayedMessageEntity>

    /** Removes every message (used before a restore replaces the history). */
    @Query("DELETE FROM relayed_messages")
    fun clearAll(): Int

    @Insert
    fun insertAll(messages: List<RelayedMessageEntity>)
}
