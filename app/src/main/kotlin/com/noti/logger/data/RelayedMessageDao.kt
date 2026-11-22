package com.noti.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Cheap fingerprint of the relayed_messages table, for change-driven UI refreshes. Room re-runs
 *  the query on every table invalidation; comparing values filters out no-op invalidations (e.g.
 *  this DAO's own markRead, which changes none of these). */
data class ChatChangeToken(val rowCount: Int, val statusSum: Int, val maxId: Long)

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

    /** The [limit] messages just older than (beforeAt, beforeId), oldest first: the page above the
     *  oldest one shown. Keyed on the last row rather than an offset, so messages arriving in the
     *  meantime can't shift the page into duplicates or a gap. */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM relayed_messages WHERE sender = :sender
              AND (receivedAt < :beforeAt OR (receivedAt = :beforeAt AND id < :beforeId))
            ORDER BY receivedAt DESC, id DESC LIMIT :limit
        ) ORDER BY receivedAt ASC, id ASC
        """
    )
    fun messagesBefore(sender: String, beforeAt: Long, beforeId: Long, limit: Int): List<RelayedMessageEntity>

    /** Everything from (fromAt, fromId) to the newest, oldest first: the whole window already shown,
     *  for a live refresh that keeps every loaded page. */
    @Query(
        """
        SELECT * FROM relayed_messages WHERE sender = :sender
          AND (receivedAt > :fromAt OR (receivedAt = :fromAt AND id >= :fromId))
        ORDER BY receivedAt ASC, id ASC
        """
    )
    fun messagesFrom(sender: String, fromAt: Long, fromId: Long): List<RelayedMessageEntity>

    @Query("DELETE FROM relayed_messages WHERE sender = :sender")
    fun deleteConversation(sender: String)

    /** Batched form of [deleteConversation] for multi-select: one transaction instead of one per
     *  conversation. */
    @Query("DELETE FROM relayed_messages WHERE sender IN (:senders)")
    fun deleteConversations(senders: List<String>)

    @Query("DELETE FROM relayed_messages WHERE id = :id")
    fun deleteMessage(id: Long)

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

    /** Stored copies of one SMS: same content key *and* the same received time. The key is sender,
     *  text and sent time, and sent times are only to the second - two genuinely separate identical
     *  SMS (a repeated OTP, "OK" twice) shared it and the second was dropped. The received time is
     *  the companion's to the millisecond, and a re-sent copy (repush, catch-up) carries the same. */
    @Query("SELECT COUNT(*) FROM relayed_messages WHERE dedupe = :key AND receivedAt = :receivedAt")
    fun countCopies(key: String, receivedAt: Long): Int

    /** How many stored messages carry this content-dedupe key (for missed-SMS dedup). */
    @Query("SELECT COUNT(*) FROM relayed_messages WHERE dedupe = :key")
    fun countByDedupe(key: String): Int

    /** A page of every stored message after row [afterId], by id (for streaming a backup). */
    @Query("SELECT * FROM relayed_messages WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    fun messagesAfterId(afterId: Long, limit: Int): List<RelayedMessageEntity>

    /** Every stored message (for export/backup). */
    @Query("SELECT * FROM relayed_messages ORDER BY receivedAt ASC, id ASC")
    fun allMessages(): List<RelayedMessageEntity>

    /** Removes every message (used before a restore replaces the history). */
    @Query("DELETE FROM relayed_messages")
    fun clearAll(): Int

    /** Retention purge: drops messages older than the cutoff (runs after inserts). */
    /** Companion-clock time of the newest relayed SMS held (0 if none) - where a catch-up starts. */
    @Query("SELECT IFNULL(MAX(receivedAt), 0) FROM relayed_messages WHERE outgoing = 0")
    fun newestIncomingAt(): Long

    @Query("DELETE FROM relayed_messages WHERE receivedAt < :cutoff")
    fun purgeOlderThan(cutoff: Long): Int

    /** Change fingerprint for live refreshes: reacts to inserts/deletes (rowCount, maxId) AND to
     *  delivery-status updates. Status uses SUM, not MAX: [updateStatus] only ever raises a row's
     *  status, so every ack changes the sum, whereas MAX stopped changing once any one message in
     *  the table had reached delivered/failed - and ticks then never updated live again. */
    @Query(
        // Not "AS rows": ROWS is an SQLite keyword (window frames), and Room 2.8's parser rejects it.
        "SELECT COUNT(*) AS rowCount, IFNULL(SUM(status), 0) AS statusSum, IFNULL(MAX(id), 0) AS maxId " +
            "FROM relayed_messages"
    )
    fun changeToken(): Flow<ChatChangeToken>

    @Insert
    fun insertAll(messages: List<RelayedMessageEntity>)

    /** Atomically replaces the whole message history (used by a backup restore): clearing and
     *  re-inserting run as one transaction, so a failure partway - a bad row, a DB error, the
     *  process dying mid-restore - can't leave the history emptied without the new rows in place. */
    @Transaction
    fun replaceAll(messages: List<RelayedMessageEntity>) {
        clearAll()
        insertAll(messages)
    }

    /** Inbound relayed messages since [since], for the Reminder rulebook scan. Outgoing messages
     *  are excluded - a bill never arrives in something you sent. */
    @Query("SELECT * FROM relayed_messages WHERE outgoing = 0 AND receivedAt >= :since ORDER BY receivedAt ASC")
    fun receivedSince(since: Long): List<RelayedMessageEntity>

    /** Inbound relayed messages stored after row [afterId], for the incremental Reminder scan.
     *  By row id, not [RelayedMessageEntity.receivedAt]: that is the SMS's own timestamp, so a
     *  message delivered late (missed-SMS sync, catch-up) carries a time older than the last scan
     *  and a time window would skip it. Still floored at [notBefore]: a repush backfills the whole
     *  SMS history as fresh rows, and without the floor one scan turned years of old statements
     *  into a tab full of overdue bills. */
    @Query("SELECT * FROM relayed_messages WHERE outgoing = 0 AND id > :afterId AND receivedAt >= :notBefore ORDER BY id ASC")
    fun receivedAfterId(afterId: Long, notBefore: Long): List<RelayedMessageEntity>
}
