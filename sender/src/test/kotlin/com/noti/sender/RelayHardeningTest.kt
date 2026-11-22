package com.noti.sender

import com.noti.sender.SenderPipeline.SendOutcome
import com.noti.sender.sms.CapturedSms
import com.noti.sender.sms.CatchUpHandler
import com.noti.sender.sms.SmsInbox
import com.noti.shared.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHardeningTest {

    // ---- FCM outcome: a 400 is the setup, not the SMS ----

    @Test
    fun `fcm 400 for a bad token holds the SMS instead of skipping it`() {
        assertEquals(SendOutcome.BLOCKED, SenderPipeline.classifyFcm(400, "INVALID_ARGUMENT: The registration token is not a valid FCM registration token"))
    }

    @Test
    fun `fcm 400 for an oversized payload skips just that SMS`() {
        assertEquals(SendOutcome.PERMANENT, SenderPipeline.classifyFcm(400, "INVALID_ARGUMENT: Android message is too big"))
        assertEquals(SendOutcome.PERMANENT, SenderPipeline.classifyFcm(413, ""))
    }

    @Test
    fun `fcm setup and transient codes`() {
        listOf(401, 403, 404).forEach { assertEquals(SendOutcome.BLOCKED, SenderPipeline.classifyFcm(it, "")) }
        listOf(-1, 429, 500, 503).forEach { assertEquals(SendOutcome.TRANSIENT, SenderPipeline.classifyFcm(it, "")) }
        assertEquals(SendOutcome.DELIVERED, SenderPipeline.classifyFcm(200, ""))
    }

    // ---- Webhook outcome: redirects are a setup error ----

    @Test
    fun `webhook redirect holds the SMS`() {
        listOf(301, 302, 307, 308).forEach { assertEquals(SendOutcome.BLOCKED, SenderPipeline.classifyWebhook(it)) }
    }

    @Test
    fun `webhook codes`() {
        assertEquals(SendOutcome.DELIVERED, SenderPipeline.classifyWebhook(204))
        assertEquals(SendOutcome.BLOCKED, SenderPipeline.classifyWebhook(401))
        assertEquals(SendOutcome.TRANSIENT, SenderPipeline.classifyWebhook(503))
        assertEquals(SendOutcome.PERMANENT, SenderPipeline.classifyWebhook(422))
    }

    // ---- SMS id reuse ----

    private fun sms(id: Long, at: Long) = CapturedSms("+1", "x", at, at, "", id)

    @Test
    fun `reused ids below the mark are still sent`() {
        // 98-100 relayed (mark 100 at time 1000), then deleted; the next SMS reuse 98 and 99.
        assertTrue(SmsInbox.isPending(sms(98, 2_000), markId = 100, markDate = 1_000))
        assertTrue(SmsInbox.isPending(sms(99, 2_100), markId = 100, markDate = 1_000))
        assertTrue(SmsInbox.isPending(sms(100, 2_200), markId = 100, markDate = 1_000))
    }

    @Test
    fun `what was relayed is not sent again`() {
        assertFalse(SmsInbox.isPending(sms(100, 1_000), markId = 100, markDate = 1_000))
        assertFalse(SmsInbox.isPending(sms(97, 900), markId = 100, markDate = 1_000))
        // No date known (an old install): ids only, the old behaviour.
        assertFalse(SmsInbox.isPending(sms(99, 2_000), markId = 100, markDate = 0))
        assertTrue(SmsInbox.isPending(sms(101, 0), markId = 100, markDate = 0))
    }

    @Test
    fun `the mark only moves forward`() {
        // Sending reused id 98 doesn't pull the mark back to 98 (which would resend 99 and 100).
        assertEquals(100L to 2_000L, SmsInbox.advance(100, 1_000, sms(98, 2_000)))
        assertEquals(101L to 2_000L, SmsInbox.advance(100, 2_000, sms(101, 1_500)))
    }

    // ---- Catch-up window ----

    @Test
    fun `catch-up starts where main asked, within bounds`() {
        val now = 100L * 24 * 60 * 60_000L
        assertEquals(now - 3_600_000L, CatchUpHandler.startMs(WireMessage.CatchUp(now - 3_600_000L), now))
        assertEquals(now - CatchUpHandler.DEFAULT_LOOKBACK_MS, CatchUpHandler.startMs(WireMessage.CatchUp(0), now))
        assertEquals(now - CatchUpHandler.MAX_LOOKBACK_MS, CatchUpHandler.startMs(WireMessage.CatchUp(1L), now))
        assertEquals(now, CatchUpHandler.startMs(WireMessage.CatchUp(now + 999_999L), now))
    }

    @Test
    fun `a repeat catch-up within ten minutes is ignored`() {
        val now = 50L * 60_000L
        assertFalse(CatchUpHandler.mayRun(now - 5 * 60_000L, now))
        assertTrue(CatchUpHandler.mayRun(now - 11 * 60_000L, now))
        assertTrue(CatchUpHandler.mayRun(0L, now))
    }
}
