package com.noti.logger

import com.noti.logger.work.CatchUpWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class CatchUpTest {
    @Test
    fun `catch-up starts an hour before the newest relay held`() {
        assertEquals(10_000_000L - CatchUpWorker.OVERLAP_MS, CatchUpWorker.sinceFor(10_000_000L))
    }

    @Test
    fun `nothing held asks for the companion's default look-back`() {
        assertEquals(0L, CatchUpWorker.sinceFor(0L))
    }
}
