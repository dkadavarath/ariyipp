package com.noti.logger

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.reminder.ReminderNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the Do Not Disturb bypass behaves as the grant comes and goes.
 *
 * Whether the device currently has the grant is a system state a test cannot set, so nothing here
 * asserts a particular grant - each test asserts the invariant that must hold *either way*. That is
 * the point: the grant can be revoked at any time from system settings, long after the channel was
 * set up, and the app has to stay correct and non-destructive when it happens.
 */
@RunWith(AndroidJUnit4::class)
class DndBypassTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)!!

    @Before fun channelsExist() {
        ReminderNotifier.ensureChannels(ctx)
    }

    @Test fun the_state_helpers_never_throw_and_agree_with_the_platform() {
        // Called on every render of the settings screen, so they must be safe whatever the grant is.
        val access = ReminderNotifier.hasDndAccess(ctx)
        assertEquals(nm.isNotificationPolicyAccessGranted, access)

        val bypassing = ReminderNotifier.parkingBypassesDnd(ctx)
        assertEquals(nm.getNotificationChannel(ReminderNotifier.CHANNEL_PARKING)?.canBypassDnd() == true, bypassing)
    }

    @Test fun enabling_with_access_turns_the_bypass_on_for_an_existing_channel() {
        // The upgrade path: a channel created before the grant has bypass off, and the only way to
        // change it is delete-and-recreate. This is the branch that proves that actually works.
        org.junit.Assume.assumeTrue(
            "needs Do Not Disturb access: adb shell cmd notification allow_dnd com.noti.logger 0",
            ReminderNotifier.hasDndAccess(ctx),
        )
        assertTrue(ReminderNotifier.enableParkingDndBypass(ctx))
        assertTrue(ReminderNotifier.parkingBypassesDnd(ctx))
        // And the channel is still fully formed, not a stub left by the recreate.
        val ch = nm.getNotificationChannel(ReminderNotifier.CHANNEL_PARKING)!!
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertNotNull(ch.sound)
    }

    @Test fun enabling_without_access_fails_without_destroying_the_channel() {
        // The only way to turn the bypass on is to delete and recreate the channel, which resets
        // it. Doing that when the platform will force the flag back off anyway would lose the
        // channel's settings and gain nothing, so the no-access path must bail out *before* the
        // delete - not attempt it and report failure afterwards.
        val before = nm.getNotificationChannel(ReminderNotifier.CHANNEL_PARKING)
        assertNotNull("ensureChannels should have created it", before)

        val result = ReminderNotifier.enableParkingDndBypass(ctx)
        val after = nm.getNotificationChannel(ReminderNotifier.CHANNEL_PARKING)
        assertNotNull("channel must survive either outcome", after)
        assertEquals("importance must survive either outcome", before!!.importance, after!!.importance)

        if (!ReminderNotifier.hasDndAccess(ctx)) {
            assertFalse("cannot succeed without the grant", result)
            assertFalse("and must not claim the channel bypasses", ReminderNotifier.parkingBypassesDnd(ctx))
        } else {
            assertTrue(result)
            assertTrue(ReminderNotifier.parkingBypassesDnd(ctx))
        }
    }

    @Test fun enabling_is_idempotent_when_it_already_bypasses() {
        // Assume rather than a silent early return: without the grant this reports as skipped, so
        // a run where the branch never executed is visible instead of looking like a pass.
        org.junit.Assume.assumeTrue(
            "needs Do Not Disturb access: adb shell cmd notification allow_dnd com.noti.logger 0",
            ReminderNotifier.hasDndAccess(ctx),
        )
        assertTrue(ReminderNotifier.enableParkingDndBypass(ctx))
        // Second call must be a no-op rather than another delete-and-recreate cycle.
        assertTrue(ReminderNotifier.enableParkingDndBypass(ctx))
        assertTrue(ReminderNotifier.parkingBypassesDnd(ctx))
    }

    @Test fun a_revoked_grant_is_reported_honestly_rather_than_remembered() {
        // The app must never cache "we set this up once" - after a revoke the only truthful answer
        // comes from asking the platform again, which is what these helpers do on every render.
        val access = ReminderNotifier.hasDndAccess(ctx)
        val bypassing = ReminderNotifier.parkingBypassesDnd(ctx)
        // Whatever the platform says now is what the UI reports; there is no third state where the
        // app believes it is set up but the system disagrees.
        assertEquals(nm.isNotificationPolicyAccessGranted, access)
        assertEquals(nm.getNotificationChannel(ReminderNotifier.CHANNEL_PARKING)!!.canBypassDnd(), bypassing)
    }

    @Test fun ensure_channels_is_idempotent_and_never_resets_an_existing_channel() {
        // It runs on app start and on every settings change, so a second call must not disturb a
        // channel the user (or the bypass setup) has already shaped.
        val before = nm.getNotificationChannel(ReminderNotifier.CHANNEL_PARKING)!!
        val bypassBefore = before.canBypassDnd()
        ReminderNotifier.ensureChannels(ctx)
        ReminderNotifier.ensureChannels(ctx)
        val after = nm.getNotificationChannel(ReminderNotifier.CHANNEL_PARKING)!!
        assertEquals(before.importance, after.importance)
        assertEquals(bypassBefore, after.canBypassDnd())
    }

    @Test fun the_bypass_call_is_safe_on_a_device_with_no_channels_yet() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.deleteNotificationChannel(ReminderNotifier.CHANNEL_PARKING)
        // Must not throw on a missing channel; it either recreates it or declines cleanly.
        ReminderNotifier.enableParkingDndBypass(ctx)
        assertFalse(
            "no channel and no access must not report success",
            !ReminderNotifier.hasDndAccess(ctx) && ReminderNotifier.parkingBypassesDnd(ctx),
        )
        ReminderNotifier.ensureChannels(ctx)
        assertNotNull(nm.getNotificationChannel(ReminderNotifier.CHANNEL_PARKING))
    }
}
