package com.noti.logger

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noti.logger.config.Settings
import com.noti.logger.push.PushMessageHandler
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the whole receive path — decrypt → parse → notify — minus the FCM transport (which needs
 * Play Services). Notification state is async and shared, so each test starts from a polled-clean
 * slate; the positive case polls for its post and the drop cases start empty and post nothing.
 */
@RunWith(AndroidJUnit4::class)
class PushMessageHandlerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val key = MessageCrypto.generateKeyBase64()

    @Before
    fun setup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        val s = Settings.get(ctx)
        s.relayKey = key
        s.pushInboundEnabled = true
        clearAndWait()
    }

    @After
    fun teardown() {
        val s = Settings.get(ctx)
        s.relayKey = ""
        s.pushInboundEnabled = false
        clearAndWait()
    }

    private fun clearAndWait() {
        nm.cancelAll()
        val deadline = System.currentTimeMillis() + 2000
        while (nm.activeNotifications.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun payload(title: String, body: String, encKey: String = key): Map<String, String> {
        val plain = Wire.encode(WireMessage.Relay(title = title, body = body))
        return mapOf(PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(plain, encKey))
    }

    @Test
    fun decrypts_and_shows_the_message() {
        assertTrue(PushMessageHandler.handle(ctx, payload("Bank", "Your OTP is 123456")))

        // Poll for the post (notify() can lag), then check its content.
        var posted: android.service.notification.StatusBarNotification? = null
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            posted = nm.activeNotifications.firstOrNull {
                it.notification.extras.getString(Notification.EXTRA_TITLE) == "Bank"
            }
            if (posted != null) break
            Thread.sleep(50)
        }
        assertTrue("expected a 'Bank' notification", posted != null)
        assertEquals("Your OTP is 123456", posted!!.notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun wrong_key_drops_silently() {
        val strangerKey = MessageCrypto.generateKeyBase64()
        assertFalse(PushMessageHandler.handle(ctx, payload("x", "y", encKey = strangerKey)))
        assertEquals(0, nm.activeNotifications.size) // slate is clean and nothing was posted
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
