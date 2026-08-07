package com.noti.logger

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.push.PushMessageHandler
import com.noti.shared.MessageCrypto
import com.noti.shared.RelayMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the whole receive path — decrypt → parse → notify — minus the FCM transport (which needs
 * Google Play Services, absent on this AOSP emulator). Payloads are built with the same key the
 * settings hold, exactly as the sender app would produce them.
 */
@RunWith(AndroidJUnit4::class)
class PushMessageHandlerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val key = MessageCrypto.generateKeyBase64()

    @Before
    fun setup() {
        val s = Settings.get(ctx)
        s.relayKey = key
        s.pushInboundEnabled = true
        nm.cancelAll()
    }

    @After
    fun teardown() {
        val s = Settings.get(ctx)
        s.relayKey = ""
        s.pushInboundEnabled = false
        nm.cancelAll()
    }

    /** A data payload encrypted with [encKey] (defaults to the one settings hold). */
    private fun payload(title: String, body: String, encKey: String = key): Map<String, String> {
        val plain = Json.encodeToString(RelayMessage(title, body))
        return mapOf(PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(plain, encKey))
    }

    @Test
    fun decrypts_and_shows_the_message() {
        assertTrue(PushMessageHandler.handle(ctx, payload("Bank", "Your OTP is 123456")))
        val posted = nm.activeNotifications.singleOrNull()
        assertNotNull(posted)
        val extras = posted!!.notification.extras
        assertEquals("Bank", extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Your OTP is 123456", extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun wrong_key_drops_silently() {
        val strangerKey = MessageCrypto.generateKeyBase64()
        assertFalse(PushMessageHandler.handle(ctx, payload("x", "y", encKey = strangerKey)))
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun disabled_inbound_drops() {
        Settings.get(ctx).pushInboundEnabled = false
        assertFalse(PushMessageHandler.handle(ctx, payload("x", "y")))
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun missing_key_drops() {
        Settings.get(ctx).relayKey = ""
        assertFalse(PushMessageHandler.handle(ctx, payload("x", "y")))
    }

    @Test
    fun missing_payload_field_drops() {
        assertFalse(PushMessageHandler.handle(ctx, emptyMap()))
        assertFalse(PushMessageHandler.handle(ctx, mapOf("other" to "value")))
    }

    @Test
    fun tampered_ciphertext_drops() {
        val p = payload("Bank", "secret").toMutableMap()
        val c = p[PushMessageHandler.PAYLOAD_KEY]!!
        p[PushMessageHandler.PAYLOAD_KEY] = c.dropLast(2) + "AA" // corrupt the tail
        assertFalse(PushMessageHandler.handle(ctx, p))
        assertEquals(0, nm.activeNotifications.size)
    }
}
