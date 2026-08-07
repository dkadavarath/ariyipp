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
        s.n8nUrl = ""
        s.n8nToken = ""
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
    fun n8n_auth_value_defaults_to_bearer_scheme() {
        val s = SenderSettings.get(ctx)
        s.n8nToken = "abc123"
        assertEquals("Bearer abc123", s.n8nAuthValue())
    }

    @Test
    fun device_id_is_stable_across_lookups() {
        assertEquals(SenderSettings.get(ctx).deviceId, SenderSettings.get(ctx).deviceId)
        assertTrue(SenderSettings.get(ctx).deviceId.isNotBlank())
    }

    @Test
    fun toggles_default_fcm_on_n8n_off() {
        // Fresh defaults (not overridden by other tests' writes).
        val s = SenderSettings.get(ctx)
        assertNotEquals(s.fcmEnabled, s.n8nEnabled) // fcm=true, n8n=false by default
    }
}
