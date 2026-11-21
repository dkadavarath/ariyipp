package com.noti.logger

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.ui.ComposeActivity
import com.noti.logger.ui.RelayReceiveActivity
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SendGuard
import com.noti.shared.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** S1 screenshot protection and S2 send limits, on a device. */
@RunWith(AndroidJUnit4::class)
class SecurityUiTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var savedRole: Role? = null
    private var savedKeys = true
    private var savedMessages = false

    @Before fun save() {
        val s = Settings.get(ctx)
        savedRole = s.role; savedKeys = s.protectKeyScreens; savedMessages = s.protectMessages
        s.role = Role.MAIN
    }

    @After fun restore() {
        val s = Settings.get(ctx)
        s.role = savedRole; s.protectKeyScreens = savedKeys; s.protectMessages = savedMessages
        val c = SenderSettings.get(ctx)
        c.commandSendTimes = emptyList(); c.allowlistOnly = false
        c.sendLimitPerHour = SenderSettings.DEFAULT_SEND_LIMIT_HOUR; c.sendLimitPerDay = SenderSettings.DEFAULT_SEND_LIMIT_DAY
    }

    private fun secure(a: android.app.Activity) =
        a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun defaults_protect_keys_not_messages() {
        val s = Settings.get(ctx)
        s.protectKeyScreens = true; s.protectMessages = false
        ActivityScenario.launch(RelayReceiveActivity::class.java).use { it.onActivity { a -> assertTrue(secure(a)) } }
        ActivityScenario.launch(ComposeActivity::class.java).use { it.onActivity { a -> assertFalse(secure(a)) } }
    }

    @Test fun switches_change_what_is_protected() {
        val s = Settings.get(ctx)
        s.protectKeyScreens = false; s.protectMessages = true
        ActivityScenario.launch(RelayReceiveActivity::class.java).use { it.onActivity { a -> assertFalse(secure(a)) } }
        ActivityScenario.launch(ComposeActivity::class.java).use { it.onActivity { a -> assertTrue(secure(a)) } }
    }

    @Test fun send_limit_refuses_past_the_hourly_cap_and_counts_only_sends() {
        val c = SenderSettings.get(ctx)
        c.commandSendTimes = emptyList(); c.sendLimitPerHour = 2; c.sendLimitPerDay = 50
        assertNull(SendGuard.check(ctx, "+15550001"))
        assertNull(SendGuard.check(ctx, "+15550001"))
        assertTrue(SendGuard.check(ctx, "+15550001")!!.contains("hourly"))
        assertEquals("a refused send isn't counted", 2, c.commandSendTimes.size)
    }

    @Test fun allowlist_refuses_numbers_that_never_texted() {
        val c = SenderSettings.get(ctx)
        c.allowlistOnly = true
        assertTrue(SendGuard.check(ctx, "+15559998877")!!.contains("allowlist"))
    }

    @Test fun numbers_match_across_formats() {
        assertTrue(SendGuard.isKnown("+971501234567", listOf("0501234567", "HDFCBK")))
        assertTrue(SendGuard.isKnown("hdfcbk", listOf("HDFCBK")))
        assertFalse(SendGuard.isKnown("+971509999999", listOf("0501234567")))
    }
}
