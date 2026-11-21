package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.push.CompanionStatus
import com.noti.logger.push.PushMessageHandler
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The receive half of companion status: a report arriving over the encrypted channel is decrypted,
 * stored, and made available to the Status card - with freshness judged on this device's clock.
 */
@RunWith(AndroidJUnit4::class)
class CompanionStatusTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = MessageCrypto.generateKeyBase64()

    @Before fun setup() {
        val s = Settings.get(ctx)
        s.relayKey = key
        s.companionStatusEnabled = true
        s.companionPollSeconds = DeviceStatusPolicy.DEFAULT_POLL_SECONDS
        s.lastCompanionStatus = ""
        s.lastCompanionStatusAtMs = 0L
    }

    @After fun teardown() {
        val s = Settings.get(ctx)
        s.relayKey = ""
        s.companionStatusEnabled = true
        s.lastCompanionStatus = ""
        s.lastCompanionStatusAtMs = 0L
    }

    private fun payload(msg: WireMessage): Map<String, String> =
        mapOf(PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(Wire.encodeFor(msg, Wire.Direction.COMPANION_TO_MAIN), key))

    @Test fun a_report_is_decrypted_stored_and_readable() {
        val report = WireMessage.StatusReport(
            atMs = 1_700_000_000_000L, batteryPct = 42, signalLevel = 3, networkType = "LTE",
            wifi = true, online = true, vpn = true,
        )
        assertTrue(PushMessageHandler.handle(ctx, payload(report)))

        val stored = CompanionStatus.last(ctx)
        assertNotNull(stored)
        assertEquals(42, stored!!.batteryPct)
        assertEquals(3, stored.signalLevel)
        assertEquals("LTE", stored.networkType)
        assertTrue(stored.wifi)
        assertTrue(stored.vpn)
    }

    @Test fun a_fresh_report_is_not_stale_and_is_stamped_with_our_own_clock() {
        val before = System.currentTimeMillis()
        // A companion whose clock is an hour behind must still count as a fresh reading: freshness
        // is about when it reached us, not about what its clock says.
        PushMessageHandler.handle(ctx, payload(WireMessage.StatusReport(atMs = before - 3_600_000L, batteryPct = 50)))
        val stampedAt = CompanionStatus.lastAtMs(ctx)
        assertTrue(stampedAt >= before)
        assertFalse(CompanionStatus.suspect(ctx))
    }

    @Test fun a_report_is_ignored_when_monitoring_is_off() {
        Settings.get(ctx).companionStatusEnabled = false
        // handle() still returns true - the message was ours and was understood; it just wasn't
        // acted on. What matters is that nothing was stored.
        PushMessageHandler.handle(ctx, payload(WireMessage.StatusReport(batteryPct = 11)))
        assertEquals("", Settings.get(ctx).lastCompanionStatus)
    }

    @Test fun a_report_encrypted_with_the_wrong_key_is_dropped() {
        val foreign = mapOf(
            PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(
                Wire.encodeFor(WireMessage.StatusReport(batteryPct = 7), Wire.Direction.COMPANION_TO_MAIN), MessageCrypto.generateKeyBase64(),
            ),
        )
        assertFalse(PushMessageHandler.handle(ctx, foreign))
        assertEquals("", Settings.get(ctx).lastCompanionStatus)
    }

    @Test fun the_last_report_survives_as_persisted_state() {
        // The card paints a cold start from this, before the first poll of the new session lands.
        PushMessageHandler.handle(ctx, payload(WireMessage.StatusReport(batteryPct = 88, mobileData = true)))
        val encoded = Settings.get(ctx).lastCompanionStatus
        assertTrue(encoded.isNotBlank())
        val decoded = Wire.decode(encoded) as WireMessage.StatusReport
        assertEquals(88, decoded.batteryPct)
        assertTrue(decoded.mobileData)
    }

    @Test fun alert_triggers_round_trip_intact() {
        // Main branches on the trigger to decide whether to raise a notification, so it has to
        // survive the wire exactly.
        PushMessageHandler.handle(
            ctx,
            payload(
                WireMessage.StatusReport(
                    trigger = WireMessage.StatusReport.WIFI_LOST, batteryPct = 60, wifiDownMinutes = 12, mobileData = true,
                )
            ),
        )
        val stored = CompanionStatus.last(ctx)!!
        assertEquals(WireMessage.StatusReport.WIFI_LOST, stored.trigger)
        assertEquals(12, stored.wifiDownMinutes)
        assertTrue(WireMessage.StatusReport.isAlert(stored.trigger))
    }
}
