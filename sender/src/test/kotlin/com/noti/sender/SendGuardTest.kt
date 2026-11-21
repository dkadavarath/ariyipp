package com.noti.sender

import com.noti.sender.sms.SendGuard
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendGuardTest {
    private val now = 10L * 24 * 3_600_000L
    private fun minutesAgo(vararg m: Int) = m.map { now - it * 60_000L }

    @Test fun `under both limits may send`() {
        assertNull(SendGuard.limitReason(minutesAgo(1, 2, 3), now, perHour = 20, perDay = 50))
    }

    @Test fun `the hourly limit counts only the last hour`() {
        val full = (1..20).map { now - it * 60_000L }
        assertTrue(SendGuard.limitReason(full, now, 20, 50)!!.contains("hourly"))
        val older = (61..80).map { now - it * 60_000L }
        assertNull(SendGuard.limitReason(older, now, 20, 50))
    }

    @Test fun `the daily limit counts the last 24 hours`() {
        val spread = (1..50).map { now - it * 20 * 60_000L } // every 20 min over ~17 h
        assertTrue(SendGuard.limitReason(spread, now, 20, 50)!!.contains("daily"))
        val yesterday = (1..50).map { now - 25 * 3_600_000L - it * 60_000L }
        assertNull(SendGuard.limitReason(yesterday, now, 20, 50))
    }
}
