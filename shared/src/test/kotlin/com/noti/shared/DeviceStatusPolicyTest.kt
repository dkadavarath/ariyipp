package com.noti.shared

import com.noti.shared.DeviceStatusPolicy.BatteryDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusPolicyTest {

    private val now = 1_000_000_000_000L
    private val minute = 60_000L

    // ---- Clamping ----

    @Test fun `thresholds clamp into their allowed ranges`() {
        assertEquals(DeviceStatusPolicy.MIN_BATTERY_PCT, DeviceStatusPolicy.clampBatteryPct(0))
        assertEquals(DeviceStatusPolicy.MIN_BATTERY_PCT, DeviceStatusPolicy.clampBatteryPct(-40))
        assertEquals(DeviceStatusPolicy.MAX_BATTERY_PCT, DeviceStatusPolicy.clampBatteryPct(100))
        assertEquals(25, DeviceStatusPolicy.clampBatteryPct(25))

        assertEquals(DeviceStatusPolicy.MIN_WIFI_LOSS_MINUTES, DeviceStatusPolicy.clampWifiLossMinutes(0))
        assertEquals(DeviceStatusPolicy.MAX_WIFI_LOSS_MINUTES, DeviceStatusPolicy.clampWifiLossMinutes(99_999))

        assertEquals(DeviceStatusPolicy.MIN_POLL_SECONDS, DeviceStatusPolicy.clampPollSeconds(1))
        assertEquals(DeviceStatusPolicy.MAX_POLL_SECONDS, DeviceStatusPolicy.clampPollSeconds(3_600))
        assertEquals(10, DeviceStatusPolicy.clampPollSeconds(10))
    }

    @Test fun `a hostile subscription request is capped, not honoured`() {
        // Without the cap, one replayed request could pin the companion into streaming for hours.
        val deadline = DeviceStatusPolicy.deadlineFor(999_999, now)
        assertEquals(now + DeviceStatusPolicy.MAX_SUBSCRIPTION_SECONDS * 1000L, deadline)
    }

    // ---- Subscription window ----

    @Test fun `zero seconds cancels the subscription`() {
        assertEquals(0L, DeviceStatusPolicy.deadlineFor(0, now))
        assertFalse(DeviceStatusPolicy.subscriptionActive(0L, now))
    }

    @Test fun `a negative request also cancels rather than extending`() {
        assertEquals(0L, DeviceStatusPolicy.deadlineFor(-30, now))
    }

    @Test fun `subscription is active until the deadline passes`() {
        val deadline = DeviceStatusPolicy.deadlineFor(DeviceStatusPolicy.SUBSCRIPTION_SECONDS, now)
        assertTrue(DeviceStatusPolicy.subscriptionActive(deadline, now))
        assertTrue(DeviceStatusPolicy.subscriptionActive(deadline, deadline - 1))
        assertFalse(DeviceStatusPolicy.subscriptionActive(deadline, deadline))
        assertFalse(DeviceStatusPolicy.subscriptionActive(deadline, deadline + 1))
    }

    @Test fun `renewing leaves comfortable slack inside the window`() {
        // One dropped renewal must not produce a visible gap in the readout.
        assertTrue(DeviceStatusPolicy.RENEW_SECONDS * 2 < DeviceStatusPolicy.SUBSCRIPTION_SECONDS)
    }

    // ---- Battery ----

    @Test fun `crossing below the threshold alerts once`() {
        assertEquals(BatteryDecision.ALERT, DeviceStatusPolicy.batteryDecision(19, false, 20, armed = true))
        // Already alerted for this episode: silence until it recovers.
        assertEquals(BatteryDecision.NONE, DeviceStatusPolicy.batteryDecision(19, false, 20, armed = false))
        assertEquals(BatteryDecision.NONE, DeviceStatusPolicy.batteryDecision(3, false, 20, armed = false))
    }

    @Test fun `sitting exactly on the threshold counts as below`() {
        assertEquals(BatteryDecision.ALERT, DeviceStatusPolicy.batteryDecision(20, false, 20, armed = true))
    }

    @Test fun `above the threshold with nothing outstanding says nothing`() {
        assertEquals(BatteryDecision.NONE, DeviceStatusPolicy.batteryDecision(80, false, 20, armed = true))
    }

    @Test fun `hysteresis stops re-arming the moment it ticks back up`() {
        // 21% after a 20% alert must NOT re-arm, or a phone hovering on the line alerts repeatedly.
        assertEquals(BatteryDecision.NONE, DeviceStatusPolicy.batteryDecision(21, false, 20, armed = false))
        assertEquals(BatteryDecision.NONE, DeviceStatusPolicy.batteryDecision(24, false, 20, armed = false))
        assertEquals(BatteryDecision.RECOVERED, DeviceStatusPolicy.batteryDecision(25, false, 20, armed = false))
    }

    @Test fun `putting it on charge counts as recovery`() {
        assertEquals(BatteryDecision.RECOVERED, DeviceStatusPolicy.batteryDecision(9, true, 20, armed = false))
        // ...and suppresses the alert entirely while charging, even below the threshold.
        assertEquals(BatteryDecision.NONE, DeviceStatusPolicy.batteryDecision(9, true, 20, armed = true))
    }

    @Test fun `an unknown battery reading is never an alert`() {
        assertEquals(BatteryDecision.NONE, DeviceStatusPolicy.batteryDecision(-1, false, 20, armed = true))
        assertEquals(BatteryDecision.NONE, DeviceStatusPolicy.batteryDecision(-1, false, 20, armed = false))
    }

    // ---- Wifi ----

    @Test fun `wifi up is never due and reports zero downtime`() {
        assertFalse(DeviceStatusPolicy.wifiAlertDue(0L, now, 10))
        assertEquals(0, DeviceStatusPolicy.downMinutes(0L, now))
        assertEquals(0L, DeviceStatusPolicy.wifiAlertDelayMs(0L, now, 10))
    }

    @Test fun `wifi alert waits out the full threshold`() {
        val lostAt = now - 9 * minute
        assertFalse(DeviceStatusPolicy.wifiAlertDue(lostAt, now, 10))
        assertEquals(minute, DeviceStatusPolicy.wifiAlertDelayMs(lostAt, now, 10))
        assertTrue(DeviceStatusPolicy.wifiAlertDue(lostAt, now + minute, 10))
        assertEquals(0L, DeviceStatusPolicy.wifiAlertDelayMs(lostAt, now + minute, 10))
    }

    @Test fun `downtime is whole minutes and never negative`() {
        assertEquals(3, DeviceStatusPolicy.downMinutes(now - 3 * minute - 5_000, now))
        assertEquals(0, DeviceStatusPolicy.downMinutes(now - 30_000, now))
        // A companion whose clock jumped backwards must not report negative downtime.
        assertEquals(0, DeviceStatusPolicy.downMinutes(now + 5 * minute, now))
    }

    // ---- Readout freshness ----

    private val poll = 10

    @Test fun `a fresh reading has missed nothing`() {
        assertEquals(0, DeviceStatusPolicy.missedUpdates(now - 3_000, now, poll))
        assertFalse(DeviceStatusPolicy.readoutSuspect(now - 3_000, now, poll))
    }

    @Test fun `one missed update is absorbed silently`() {
        // The last known values keep showing: a single dropped push is unremarkable, and blanking a
        // good reading over it would be worse than showing one a few seconds old.
        val oneMiss = now - 11_000
        assertEquals(1, DeviceStatusPolicy.missedUpdates(oneMiss, now, poll))
        assertFalse(DeviceStatusPolicy.readoutSuspect(oneMiss, now, poll))
    }

    @Test fun `the second miss is what marks the readout`() {
        val twoMisses = now - 21_000
        assertEquals(2, DeviceStatusPolicy.missedUpdates(twoMisses, now, poll))
        assertTrue(DeviceStatusPolicy.readoutSuspect(twoMisses, now, poll))
    }

    @Test fun `misses keep counting up once it is wrong`() {
        assertEquals(6, DeviceStatusPolicy.missedUpdates(now - 61_000, now, poll))
        assertTrue(DeviceStatusPolicy.readoutSuspect(now - 61_000, now, poll))
    }

    @Test fun `nothing received yet is not a miss`() {
        // Never having heard from the companion is the empty state, not a failure to report.
        assertEquals(0, DeviceStatusPolicy.missedUpdates(0L, now, poll))
        assertFalse(DeviceStatusPolicy.readoutSuspect(0L, now, poll))
    }

    @Test fun `a slower poll interval widens the window proportionally`() {
        // 30s polling: 40s of silence is one miss, not four.
        assertEquals(1, DeviceStatusPolicy.missedUpdates(now - 40_000, now, 30))
        assertFalse(DeviceStatusPolicy.readoutSuspect(now - 40_000, now, 30))
        assertTrue(DeviceStatusPolicy.readoutSuspect(now - 61_000, now, 30))
    }
}
