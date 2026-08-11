package com.noti.logger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A message relayed from sndi (e.g. a forwarded SMS), stored so noti can show a chat history.
 * Grouped into conversations by [sender]. [outgoing] is 0 for received messages; 1 is reserved for
 * messages composed in noti and sent from sndi (Phase B).
 */
@Entity(
    tableName = "relayed_messages",
    indices = [Index("sender"), Index("receivedAt"), Index("dedupe")]
)
data class RelayedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val sim: String,
    val body: String,
    val receivedAt: Long,
    val outgoing: Int = 0,
    /** 0 = unread, 1 = read. Only meaningful for incoming (outgoing == 0) messages. */
    val read: Int = 0,
    /** Stable content key from the sender; drops duplicates (live relay vs missed-sync). Blank = none. */
    val dedupe: String = "",
)

/** One conversation row: the sender, its most recent message/time, count, and unread count. */
data class ConversationSummary(
    val sender: String,
    val lastBody: String,
    val lastAt: Long,
    val count: Int,
    val unread: Int,
)
