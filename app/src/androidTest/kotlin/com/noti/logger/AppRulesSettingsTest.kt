package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.redact.AppRule
import com.noti.logger.redact.RawNotification
import com.noti.logger.redact.RedactedResult
import com.noti.logger.redact.RedactionRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that per-app keyword filters and notes round-trip through the real (encrypted)
 * Settings store and drive RedactionRules exactly as the capture path uses it — including the
 * cached redaction snapshot being invalidated when the rules change.
 */
@RunWith(AndroidJUnit4::class)
class AppRulesSettingsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun raw(pkg: String, text: String?) = RawNotification(pkg, null, text, null, null, "msg")

    /** Re-reads config the same way NotiListenerService does. */
    private fun rules() = RedactionRules(Settings.get(ctx).redactionConfig())

    // Settings persist in the app's real store, so each test starts from a known-clean state
    // rather than inheriting whatever the device happened to hold.
    @Before
    fun clean() = reset()

    @After
    fun reset() {
        val s = Settings.get(ctx)
        s.appRules = emptyMap()
        s.includedPackages = emptySet()
        s.captureBody = true
    }

    @Test
    fun keywords_persisted_in_settings_filter_capture() {
        Settings.get(ctx).appRules = mapOf("com.shop" to AppRule(keywords = listOf("delivered")))

        assertTrue(rules().apply(raw("com.shop", "Parcel DELIVERED")) is RedactedResult.Kept)
        assertEquals(RedactedResult.Dropped, rules().apply(raw("com.shop", "Weekend sale")))
    }

    @Test
    fun notes_persisted_in_settings_are_appended_to_text() {
        Settings.get(ctx).appRules = mapOf("com.shop" to AppRule(notes = "shopping"))

        val kept = rules().apply(raw("com.shop", "Parcel delivered")) as RedactedResult.Kept
        assertEquals("Parcel delivered\nshopping", kept.text)
    }

    @Test
    fun app_without_a_stored_rule_is_unfiltered() {
        Settings.get(ctx).appRules = mapOf("com.shop" to AppRule(keywords = listOf("delivered")))

        assertTrue(rules().apply(raw("com.other", "anything at all")) is RedactedResult.Kept)
    }

    @Test
    fun changing_rules_invalidates_the_cached_redaction_config() {
        val s = Settings.get(ctx)
        s.appRules = mapOf("com.shop" to AppRule(keywords = listOf("delivered")))
        // Prime the cache.
        assertEquals(RedactedResult.Dropped, rules().apply(raw("com.shop", "Weekend sale")))

        s.appRules = mapOf("com.shop" to AppRule(keywords = listOf("sale")))
        assertTrue(rules().apply(raw("com.shop", "Weekend sale")) is RedactedResult.Kept)
    }

    @Test
    fun rules_survive_a_full_settings_round_trip() {
        Settings.get(ctx).appRules = mapOf(
            "com.shop" to AppRule(keywords = listOf("delivered", "out for delivery"), notes = "work phone"),
            "com.bank" to AppRule(keywords = listOf("credited"))
        )

        val reread = Settings.get(ctx).appRules
        assertEquals(listOf("delivered", "out for delivery"), reread["com.shop"]?.keywords)
        assertEquals("work phone", reread["com.shop"]?.notes)
        assertEquals(listOf("credited"), reread["com.bank"]?.keywords)
    }

    @Test
    fun allowlist_still_wins_over_a_matching_app_rule() {
        val s = Settings.get(ctx)
        s.includedPackages = setOf("com.included")
        s.appRules = mapOf("com.shop" to AppRule(keywords = listOf("delivered")))

        // com.shop is not in the allowlist, so its rule never gets a say.
        assertEquals(RedactedResult.Dropped, rules().apply(raw("com.shop", "Parcel delivered")))
    }
}
