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
    indices = [
        Index("sender"),
        Index("receivedAt"),
        Index("dedupe"),
        // Lets SQLite satisfy the "latest message per sender" correlated subquery in
        // RelayedMessageDao (conversations/searchConversations) with an index seek instead of
        // an O(N^2) scan as history grows.
        Index(value = ["sender", "receivedAt"]),
    ]
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
    /** Delivery status for an outgoing message: 0 = pending/unknown, 1 = received by the companion,
     *  2 = handed off to the SIM, 3 = a carrier delivery report confirmed it reached the recipient
     *  (not every carrier sends these - staying at 2 doesn't mean it failed), 4 = failed. Meaningless
     *  for incoming (outgoing == 0) messages. See [com.noti.shared.WireMessage.DeliveryAck]. */
    val status: Int = 0,
)

/** One conversation row: the sender, its most recent message/time, count, and unread count. */
data class ConversationSummary(
    val sender: String,
    val lastBody: String,
    val lastAt: Long,
    val count: Int,
    val unread: Int,
)
