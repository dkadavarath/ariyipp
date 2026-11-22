package com.noti.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuardTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private fun mark(sentAt: Long, seenAt: Long = sentAt) = ReplayGuard.Mark(sentAt, seenAt)

    @Test fun `a newer message is new, an older or repeated one is a replay`() {
        assertTrue(ReplayGuard.isNew(now, mark(0, 0), now))
        assertTrue(ReplayGuard.isNew(now, mark(now - min), now))
        assertFalse(ReplayGuard.isNew(now - min, mark(now), now))
        assertFalse(ReplayGuard.isNew(now, mark(now), now))
    }

    @Test fun `an unstamped message is never new`() = assertFalse(ReplayGuard.isNew(0, mark(0, 0), now))

    @Test fun `a peer whose clock is an hour off is still heard`() {
        val off = 60 * min
        // Ahead: every new stamp is after the last one.
        assertTrue(ReplayGuard.isNew(now + off, mark(now + off - 15 * min, now - 15 * min), now))
        // Behind, likewise.
        assertTrue(ReplayGuard.isNew(now - off, mark(now - off - 15 * min, now - 15 * min), now))
        // And a replay from either is still refused.
        assertFalse(ReplayGuard.isNew(now + off - 30 * min, mark(now + off - 15 * min, now - 15 * min), now))
        assertFalse(ReplayGuard.isNew(now - off - 30 * min, mark(now - off - 15 * min, now - 15 * min), now))
    }

    @Test fun `a peer clock corrected back from ahead is heard again`() {
        val wasAhead = mark(now + 50 * min, now - 10 * min)
        assertTrue(ReplayGuard.isNew(now, wasAhead, now))
        // An old copy stamped with the wrong (ahead) time is not.
        assertFalse(ReplayGuard.isNew(now + 40 * min, wasAhead, now))
    }

    @Test fun `after a clock correction, copies stamped while the peer ran ahead stay refused`() {
        // The peer ran an hour ahead: at real 10:00 it stamped "11:00" (say, a heartbeat-off).
        val h = 60 * min
        var mark = ReplayGuard.next(mark(0, 0), now + h, now)
        // Five minutes later its clock is fixed; its next message, stamped right, is taken.
        val fixed = now + 5 * min
        assertTrue(ReplayGuard.isNew(fixed, mark, fixed))
        mark = ReplayGuard.next(mark, fixed, fixed)
        // A replay of the "11:00" copy at real 10:10 is newer than the mark - but not trusted.
        assertFalse(ReplayGuard.isNew(now + h, mark, now + 10 * min))
        // Genuine messages keep coming through...
        assertTrue(ReplayGuard.isNew(now + 15 * min, mark, now + 15 * min))
        mark = ReplayGuard.next(mark, now + 15 * min, now + 15 * min)
        // ...and once real time passes the old high stamp, the floor no longer matters.
        assertTrue(ReplayGuard.isNew(now + h + min, mark, now + h + min))
        assertEquals(0L, ReplayGuard.next(mark, now + h + min, now + h + min).floor)
    }

    @Test fun `a replay from an in-sync peer can't pass as a clock correction`() =
        assertFalse(ReplayGuard.isNew(now - min, mark(now - 30_000, now - 30_000), now))

    @Test fun `the stamp goes inside the envelope and comes back out`() {
        val text = Wire.encodeFor(WireMessage.Heartbeat(), Wire.Direction.MAIN_TO_COMPANION, sentAt = 1234L)
        assertEquals(1234L, Wire.sentAt(text))
        assertEquals(WireMessage.Heartbeat(), Wire.decodeFrom(text, Wire.Direction.MAIN_TO_COMPANION))
        assertEquals(0L, Wire.sentAt(Wire.encode(WireMessage.Heartbeat())))
    }
}
