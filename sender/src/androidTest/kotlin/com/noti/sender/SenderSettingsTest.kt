package com.noti.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.sender.config.SenderSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Confirms the encrypted store round-trips the sender's secrets and config. */
@RunWith(AndroidJUnit4::class)
class SenderSettingsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clear() {
        val s = SenderSettings.get(ctx)
        s.serviceAccountJson = ""
        s.relayKey = ""
        s.notiFcmToken = ""
        s.myFcmToken = ""
        s.announcedToken = ""
        s.n8nUrl = ""
        s.n8nToken = ""
        s.n8nAuthHeaderPrefix = "Bearer "
        s.sim1Name = ""
        s.sim2Name = ""
    }

    @Test
    fun sim_names_map_slots_with_defaults() {
        val s = SenderSettings.get(ctx)
        s.sim1Name = "e&"
        s.sim2Name = ""
        assertEquals("e&", s.simName(0))
        assertEquals("SIM 2", s.simName(1)) // blank falls back to the default label
    }

    @Test
    fun round_trips_secrets_and_config() {
        val s = SenderSettings.get(ctx)
        s.serviceAccountJson = """{"type":"service_account"}"""
        s.relayKey = "  Zm9v  " // trimmed on write
        s.notiFcmToken = "token-abc"
        s.n8nUrl = "https://n8n.example/webhook/x"
        s.n8nToken = "secret"

        assertEquals("""{"type":"service_account"}""", s.serviceAccountJson)
        assertEquals("Zm9v", s.relayKey)
        assertEquals("token-abc", s.notiFcmToken)
        assertEquals("https://n8n.example/webhook/x", s.n8nUrl)
        assertEquals("secret", s.n8nToken)
    }

    @Test
    fun n8n_auth_value_concatenates_prefix_and_token() {
        val s = SenderSettings.get(ctx)
        s.n8nAuthHeaderPrefix = "Bearer " // set explicitly; don't rely on persisted default
        s.n8nToken = "abc123"
        assertEquals("Bearer abc123", s.n8nAuthValue())
    }

    @Test
    fun device_id_is_stable_across_lookups() {
        assertEquals(SenderSettings.get(ctx).deviceId, SenderSettings.get(ctx).deviceId)
        assertTrue(SenderSettings.get(ctx).deviceId.isNotBlank())
    }

    // ---- Memoized hot secrets: writes must invalidate the cache ----

    @Test
    fun memoized_secrets_reflect_updates_immediately() {
        val s = SenderSettings.get(ctx)

        s.relayKey = "key-one"
        assertEquals("key-one", s.relayKey)
        s.relayKey = "  key-two  " // trimmed on write
        assertEquals("write invalidates the cached value", "key-two", s.relayKey)

        s.notiFcmToken = "tok-one"
        assertEquals("tok-one", s.notiFcmToken)
        s.notiFcmToken = "tok-two"
        assertEquals("tok-two", s.notiFcmToken)

        s.serviceAccountJson = """{"a":1}"""
        assertEquals("""{"a":1}""", s.serviceAccountJson)
        s.serviceAccountJson = """{"b":2}"""
        assertEquals("""{"b":2}""", s.serviceAccountJson)
    }

    @Test
    fun announced_token_defaults_blank_and_round_trips() {
        val s = SenderSettings.get(ctx)
        s.announcedToken = ""
        assertEquals("", s.announcedToken)

        // The process-start gate: announce only when the live token differs from the announced one.
        s.myFcmToken = "token-current"
        assertTrue(s.myFcmToken != s.announcedToken)
        s.announcedToken = s.myFcmToken
        assertEquals("after a successful announce the two match (no re-announce)", s.myFcmToken, s.announcedToken)
        s.myFcmToken = "token-rotated"
        assertTrue("a rotation re-arms the announce", s.myFcmToken != s.announcedToken)
    }
}
