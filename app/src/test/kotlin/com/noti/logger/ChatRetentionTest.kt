package com.noti.logger

import com.noti.logger.data.ChatRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRetentionTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test
    fun zero_or_negative_days_keeps_everything() {
        assertNull(ChatRetention.cutoff(0, now))
        assertNull(ChatRetention.cutoff(-5, now))
        assertTrue(ChatRetention.keeps(now - 3650 * day, 0, now))
    }

    @Test
    fun positive_days_drops_older_rows() {
        assertEquals(now - 7 * day, ChatRetention.cutoff(7, now))
        assertTrue(ChatRetention.keeps(now - 6 * day, 7, now))
        assertFalse(ChatRetention.keeps(now - 8 * day, 7, now))
    }

    @Test
    fun only_recent_messages_notify() {
        assertTrue(ChatRetention.notifies(now - 60_000, now))
        assertTrue(ChatRetention.notifies(now - ChatRetention.NOTIFY_MAX_AGE_MS, now))
        assertFalse(ChatRetention.notifies(now - ChatRetention.NOTIFY_MAX_AGE_MS - 1, now))
        assertFalse(ChatRetention.notifies(now - 30 * day, now))
    }
}
