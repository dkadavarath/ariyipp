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

    // ---- Multi-part relays (long bodies split under FCM's 4096-byte cap) ----

    /** Rows are keyed by RelayTitle.parse(title) - "Chunk Sender" has no " on " suffix, so it IS the sender. */
    private val chunkSender = "Chunk Sender"

    /** One encrypted push carrying the [i]th of [parts] body slices. Time is fresh - an old
     *  timestamp would get the assembled row purged by retention the moment it lands. */
    private fun partPayload(i: Int, parts: Int, slice: String, dedupe: String): Map<String, String> {
        val plain = Wire.encode(
            WireMessage.Relay(
                title = "Chunk Sender", body = slice, dedupe = dedupe,
                time = System.currentTimeMillis() - 60_000, part = i, parts = parts,
            )
        )
        return mapOf(PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(plain, key))
    }

    private fun rowsFor(sender: String): List<com.noti.logger.data.RelayedMessageEntity> =
        com.noti.logger.data.NotiDatabase.get(ctx).relayedMessageDao().messagesFor(sender)

    private fun deleteConversation(sender: String) {
        try {
            com.noti.logger.data.NotiDatabase.get(ctx).relayedMessageDao().deleteConversation(sender)
        } catch (_: Exception) {
        }
    }

    /** Per-run dedupe key: the handler drops messages whose dedupe already exists, so re-running
     *  these tests on a device with leftover state must not collide with a previous run. */
    private fun uniq(tag: String) = "$tag-${System.nanoTime()}"

    @Test
    fun multipart_relay_reassembles_in_order_and_notifies_once() {
        deleteConversation(chunkSender)
        val dedupe = uniq("chunk-a")
        val parts = listOf("first ", "second ", "third")
        parts.indices.forEach { i ->
            assertTrue(PushMessageHandler.handle(ctx, partPayload(i, parts.size, parts[i], dedupe)))
        }

        // Exactly one row, with the body stitched back together.
        val rows = rowsFor(chunkSender)
        assertEquals(1, rows.size)
        assertEquals("first second third", rows.single().body)

        // Exactly one notification, showing the full body.
        var posted: android.service.notification.StatusBarNotification? = null
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            posted = nm.activeNotifications.firstOrNull { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Chunk Sender" }
            if (posted != null) break
            Thread.sleep(50)
        }
        assertEquals("first second third", posted?.notification?.extras?.getString(Notification.EXTRA_TEXT))

        deleteConversation(chunkSender)
    }

    @Test
    fun multipart_arriving_out_of_order_still_assembles() {
        deleteConversation(chunkSender)
        val dedupe = uniq("chunk-b")
        val slices = mapOf(2 to "!", 0 to "hel", 1 to "lo world")
        for (i in intArrayOf(2, 0, 1)) {
            assertTrue(PushMessageHandler.handle(ctx, partPayload(i, 3, slices[i]!!, dedupe)))
        }
        assertEquals("hello world!", rowsFor(chunkSender).single().body)
        deleteConversation(chunkSender)
    }

    @Test
    fun incomplete_part_set_buffers_without_storing_or_notifying() {
        deleteConversation(chunkSender)
        clearAndWait()
        // Part 1 of 3 arrives alone: handled (buffered), but nothing stored or shown yet.
        assertTrue(PushMessageHandler.handle(ctx, partPayload(1, 3, "middle", uniq("chunk-c"))))
        assertTrue(rowsFor(chunkSender).isEmpty())
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun duplicate_part_does_not_confuse_the_group() {
        deleteConversation(chunkSender)
        val dedupe = uniq("chunk-d")
        // Same part twice + the rest: still assembles exactly once.
        assertTrue(PushMessageHandler.handle(ctx, partPayload(0, 2, "a", dedupe)))
        assertTrue(PushMessageHandler.handle(ctx, partPayload(0, 2, "a", dedupe)))
        assertTrue(PushMessageHandler.handle(ctx, partPayload(1, 2, "b", dedupe)))
        assertEquals(1, rowsFor(chunkSender).size)
        assertEquals("ab", rowsFor(chunkSender).single().body)
        deleteConversation(chunkSender)
    }

    // ---- Retention purge (runs after every insert) ----

    @Test
    fun retention_purge_drops_history_older_than_the_window() {
        val dao = com.noti.logger.data.NotiDatabase.get(ctx).relayedMessageDao()
        val s = Settings.get(ctx)
        val originalDays = s.retentionDays

        try {
            s.retentionDays = 30
            // An old conversation from 40 days ago...
            dao.insert(
                com.noti.logger.data.RelayedMessageEntity(
                    sender = "+ancient", sim = "", body = "old news", receivedAt = System.currentTimeMillis() - 40L * 86_400_000
                )
            )
            // ...then any fresh inbound relay triggers the purge.
            assertTrue(PushMessageHandler.handle(ctx, payload("Bank", "fresh")))

            assertTrue("40-day-old history purged", rowsFor("+ancient").isEmpty())
            deleteConversation("+ancient")
        } finally {
            s.retentionDays = originalDays
            deleteConversation(chunkSender)
        }
    }
}
