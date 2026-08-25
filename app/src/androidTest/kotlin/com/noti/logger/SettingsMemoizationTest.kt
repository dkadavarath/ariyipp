package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guards for the memoized hot values: EncryptedSharedPreferences decrypts on every read,
 * so the hot ones are cached - these tests make sure a write is always visible to later reads
 * (i.e. the cache is invalidated on set, not just populated on get).
 */
@RunWith(AndroidJUnit4::class)
class SettingsMemoizationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val s = Settings.get(ctx)

    @After
    fun clear() {
        s.relayKey = ""
        s.serviceAccountJson = ""
        s.mutedSenders = emptySet()
    }

    @Test
    fun relayKey_updates_are_visible_immediately() {
        s.relayKey = "key-one"
        assertEquals("key-one", s.relayKey)
        s.relayKey = "  key-two  " // trimmed on write
        assertEquals("write invalidates the cached value", "key-two", s.relayKey)

        val peer = Settings.get(ctx) // same singleton, but proves persistence too
        assertEquals("key-two", peer.relayKey)
    }

    @Test
    fun serviceAccountJson_updates_are_visible_immediately() {
        s.serviceAccountJson = """{"a":1}"""
        assertEquals("""{"a":1}""", s.serviceAccountJson)
        s.serviceAccountJson = """{"b":2}"""
        assertEquals("multi-KB blob must not go stale in cache", """{"b":2}""", s.serviceAccountJson)
    }

    @Test
    fun mutedSenders_updates_are_visible_to_isMuted() {
        s.mutedSenders = emptySet()
        assertFalse(s.isMuted("+111"))

        s.mutedSenders = setOf("+111")
        assertTrue("set must invalidate the cached snapshot", s.isMuted("+111"))
        assertFalse(s.isMuted("+222"))

        s.setMuted("+222", true)
        assertTrue(s.isMuted("+222"))
        s.setMuted("+222", false)
        assertFalse("setMuted routes through the setter's invalidation", s.isMuted("+222"))
    }

    @Test
    fun repeated_reads_return_the_same_value() {
        s.relayKey = "stable-key"
        val first = s.relayKey
        repeat(5) { assertEquals(first, s.relayKey) }
    }
}
