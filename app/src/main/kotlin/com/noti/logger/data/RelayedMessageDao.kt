package com.noti.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Cheap fingerprint of the relayed_messages table, for change-driven UI refreshes. Room re-runs
 *  the query on every table invalidation; comparing values filters out no-op invalidations (e.g.
 *  this DAO's own markRead, which changes none of these). */
data class ChatChangeToken(val rows: Int, val maxStatus: Int, val maxId: Long)

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

    /** Push variant of [conversations]: Room re-emits whenever the table changes, so the list
     *  updates itself instead of polling on every onResume. */
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
    fun conversationsFlow(): Flow<List<ConversationSummary>>

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

    /** Push variant of [searchConversations]. */
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
    fun searchConversationsFlow(q: String): Flow<List<ConversationSummary>>

    /** All messages in a conversation, oldest first. */
    @Query("SELECT * FROM relayed_messages WHERE sender = :sender ORDER BY receivedAt ASC, id ASC")
    fun messagesFor(sender: String): List<RelayedMessageEntity>

    /**
     * A bounded window of a conversation, oldest first: the [limit] newest messages, skipping
     * [offset] of the newest. Keeps ChatActivity from loading (and DiffUtil from re-running over)
     * the entire history on every resume; older pages load on scroll.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM relayed_messages WHERE sender = :sender
            ORDER BY receivedAt DESC, id DESC LIMIT :limit OFFSET :offset
        ) ORDER BY receivedAt ASC, id ASC
        """
    )
    fun messagesPage(sender: String, limit: Int, offset: Int): List<RelayedMessageEntity>

    @Query("DELETE FROM relayed_messages WHERE sender = :sender")
    fun deleteConversation(sender: String)

    /** Batched form of [deleteConversation] for multi-select: one transaction instead of one per
     *  conversation. */
    @Query("DELETE FROM relayed_messages WHERE sender IN (:senders)")
    fun deleteConversations(senders: List<String>)

    @Query("DELETE FROM relayed_messages WHERE id = :id")
    fun deleteMessage(id: Long)

    /** Deletes all received (inbound) messages, keeping ones composed here. Returns rows removed.
     *  Used before a full repush from ariy so the rebuilt history has no pre-hash duplicates. */
    @Query("DELETE FROM relayed_messages WHERE outgoing = 0")
    fun deleteInbound(): Int

    /** Marks every incoming message in a conversation as read. Returns rows changed. */
    @Query("UPDATE relayed_messages SET read = 1 WHERE sender = :sender AND outgoing = 0 AND read = 0")
    fun markRead(sender: String): Int

    /** Batched form of [markRead] for multi-select: one transaction instead of one per
     *  conversation. Returns rows changed. */
    @Query("UPDATE relayed_messages SET read = 1 WHERE sender IN (:senders) AND outgoing = 0 AND read = 0")
    fun markRead(senders: List<String>): Int

    /** Marks every incoming message in every conversation as read. Returns rows changed. */
    @Query("UPDATE relayed_messages SET read = 1 WHERE outgoing = 0 AND read = 0")
    fun markAllRead(): Int

    /** Applies a delivery-ack status, but never lets an out-of-order ack regress an already more
     *  advanced status (e.g. a late "received" arriving after "sent"). Returns rows changed. */
    @Query("UPDATE relayed_messages SET status = :status WHERE id = :id AND status < :status")
    fun updateStatus(id: Long, status: Int): Int

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

    /** Retention purge: drops messages older than the cutoff (runs after inserts). */
    @Query("DELETE FROM relayed_messages WHERE receivedAt < :cutoff")
    fun purgeOlderThan(cutoff: Long): Int

    /** Change fingerprint for live refreshes: reacts to inserts/deletes (rows, maxId) AND to
     *  delivery-status updates (maxStatus), unlike a plain COUNT. */
    @Query(
        "SELECT COUNT(*) AS rows, IFNULL(MAX(status), 0) AS maxStatus, IFNULL(MAX(id), 0) AS maxId " +
            "FROM relayed_messages"
    )
    fun changeToken(): Flow<ChatChangeToken>

    @Insert
    fun insertAll(messages: List<RelayedMessageEntity>)
}
