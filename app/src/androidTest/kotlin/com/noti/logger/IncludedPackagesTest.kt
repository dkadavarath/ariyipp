package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.redact.RawNotification
import com.noti.logger.redact.RedactedResult
import com.noti.logger.redact.RedactionRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the included-apps allowlist round-trips through the real (encrypted)
 * Settings store and drives RedactionRules exactly as the capture path uses it.
 */
@RunWith(AndroidJUnit4::class)
class IncludedPackagesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun raw(pkg: String) = RawNotification(pkg, "t", "b", null, null, "msg")

    @After
    fun reset() {
        Settings.get(ctx).includedPackages = emptySet()
    }

    @Test
    fun allowlist_persisted_in_settings_filters_capture() {
        val s = Settings.get(ctx)
        s.includedPackages = setOf("com.included.app")

        // Re-read config the same way NotiListenerService does.
        val rules = RedactionRules(Settings.get(ctx).redactionConfig())
        assertTrue(rules.apply(raw("com.included.app")) is RedactedResult.Kept)
        assertEquals(RedactedResult.Dropped, rules.apply(raw("com.other.app")))
    }

    @Test
    fun empty_allowlist_captures_all() {
        val s = Settings.get(ctx)
        s.includedPackages = emptySet()
        val rules = RedactionRules(Settings.get(ctx).redactionConfig())
        assertTrue(rules.apply(raw("com.literally.anything")) is RedactedResult.Kept)
    }
}
