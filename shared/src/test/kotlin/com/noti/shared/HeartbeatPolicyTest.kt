package com.noti.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPolicyTest {

    private val now = 1_000_000_000_000L

    @Test fun `zero baseline is never stale`() {
        assertFalse(HeartbeatPolicy.isStale(0L, now))
    }

    @Test fun `fresh beat is not stale`() {
        assertFalse(HeartbeatPolicy.isStale(now - 60_000L, now))
    }

    @Test fun `beat just inside the window is not stale`() {
        val lastBeat = now - (HeartbeatPolicy.staleAfterMs - 1)
        assertFalse(HeartbeatPolicy.isStale(lastBeat, now))
    }

    @Test fun `beat past the window is stale`() {
        val lastBeat = now - (HeartbeatPolicy.staleAfterMs + 1)
        assertTrue(HeartbeatPolicy.isStale(lastBeat, now))
    }

    @Test fun `stale window is three intervals`() {
        // Guards the "tolerates 2 missed beats, trips on the 3rd" intent.
        assertTrue(HeartbeatPolicy.STALE_AFTER_MINUTES == HeartbeatPolicy.INTERVAL_MINUTES * 3)
    }
}
