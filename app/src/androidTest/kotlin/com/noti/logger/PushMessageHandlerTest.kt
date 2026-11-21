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
        val plain = Wire.encodeFor(WireMessage.Relay(title = title, body = body), Wire.Direction.COMPANION_TO_MAIN)
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
     *  timestamp would be treated as a backfill (stored without a notification). */
    private fun partPayload(i: Int, parts: Int, slice: String, dedupe: String): Map<String, String> {
        val plain = Wire.encodeFor(
            WireMessage.Relay(
                title = "Chunk Sender", body = slice, dedupe = dedupe,
                time = System.currentTimeMillis() - 60_000, part = i, parts = parts,
            ),
            Wire.Direction.COMPANION_TO_MAIN,
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

    @Test
    fun oversized_parts_count_is_dropped() {
        deleteConversation(chunkSender)
        assertFalse(PushMessageHandler.handle(ctx, partPayload(0, 1000, "x", uniq("chunk-e"))))
        assertTrue(rowsFor(chunkSender).isEmpty())
    }

    @Test
    fun out_of_range_part_index_is_dropped() {
        deleteConversation(chunkSender)
        assertFalse(PushMessageHandler.handle(ctx, partPayload(5, 3, "x", uniq("chunk-f"))))
        assertFalse(PushMessageHandler.handle(ctx, partPayload(-1, 3, "x", uniq("chunk-g"))))
        assertTrue(rowsFor(chunkSender).isEmpty())
    }

    // ---- Chat retention (its own setting; default keeps everything) ----

    private fun relayAt(title: String, body: String, time: Long, dedupe: String): Map<String, String> {
        val plain = Wire.encodeFor(WireMessage.Relay(title = title, body = body, dedupe = dedupe, time = time), Wire.Direction.COMPANION_TO_MAIN)
        return mapOf(PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(plain, key))
    }

    @Test
    fun chat_history_is_kept_by_default_whatever_the_notification_retention() {
        val dao = com.noti.logger.data.NotiDatabase.get(ctx).relayedMessageDao()
        val s = Settings.get(ctx)
        val (origNotif, origChat) = s.retentionDays to s.chatRetentionDays
        try {
            s.retentionDays = 7 // notification capture only - must not touch the chat
            s.chatRetentionDays = 0
            dao.insert(
                com.noti.logger.data.RelayedMessageEntity(
                    sender = "+ancient", sim = "", body = "old news", receivedAt = System.currentTimeMillis() - 40L * 86_400_000
                )
            )
            assertTrue(PushMessageHandler.handle(ctx, payload("Bank", "fresh")))
            assertEquals("40-day-old history kept", 1, rowsFor("+ancient").size)
        } finally {
            s.retentionDays = origNotif
            s.chatRetentionDays = origChat
            deleteConversation("+ancient")
            deleteConversation("Bank")
        }
    }

    @Test
    fun chat_retention_purges_older_history_when_set() {
        val dao = com.noti.logger.data.NotiDatabase.get(ctx).relayedMessageDao()
        val s = Settings.get(ctx)
        val orig = s.chatRetentionDays
        try {
            s.chatRetentionDays = 30
            dao.insert(
                com.noti.logger.data.RelayedMessageEntity(
                    sender = "+ancient", sim = "", body = "old news", receivedAt = System.currentTimeMillis() - 40L * 86_400_000
                )
            )
            assertTrue(PushMessageHandler.handle(ctx, payload("Bank", "fresh")))
            assertTrue("40-day-old history purged", rowsFor("+ancient").isEmpty())
        } finally {
            s.chatRetentionDays = orig
            deleteConversation("+ancient")
            deleteConversation("Bank")
        }
    }

    @Test
    fun relay_older_than_the_chat_window_is_neither_stored_nor_notified() {
        val s = Settings.get(ctx)
        val orig = s.chatRetentionDays
        try {
            s.chatRetentionDays = 7
            val old = System.currentTimeMillis() - 10L * 86_400_000
            assertTrue(PushMessageHandler.handle(ctx, relayAt("+backfill", "from last week", old, uniq("bf-a"))))
            assertTrue(rowsFor("+backfill").isEmpty())
            assertEquals(0, nm.activeNotifications.size)
        } finally {
            s.chatRetentionDays = orig
            deleteConversation("+backfill")
        }
    }

    @Test
    fun backfilled_relay_is_stored_without_a_notification() {
        val old = System.currentTimeMillis() - 3L * 86_400_000
        assertTrue(PushMessageHandler.handle(ctx, relayAt("+backfill", "from three days ago", old, uniq("bf-b"))))
        assertEquals(1, rowsFor("+backfill").size)
        Thread.sleep(300) // a post would have landed by now
        assertEquals(0, nm.activeNotifications.size)
        deleteConversation("+backfill")
    }

    // ---- Direction tags (S5) ----

    @Test
    fun untagged_push_from_an_old_companion_is_refused_and_noted_once() {
        val c = com.noti.sender.config.SenderSettings.get(ctx)
        c.peerOutdatedNotified = false
        val old = mapOf(
            PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(Wire.encode(WireMessage.Relay(title = "Old", body = "x")), key),
        )
        assertFalse(PushMessageHandler.handle(ctx, old))
        assertTrue(c.peerOutdatedNotified)
        // A current message clears it, so a later regression is reported again.
        PushMessageHandler.handle(ctx, payload("Current", "y"))
        assertFalse(c.peerOutdatedNotified)
    }

    @Test
    fun a_main_message_reflected_back_to_main_is_refused() {
        val s = Settings.get(ctx)
        val before = s.sndiFcmToken
        val reflected = mapOf(
            PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(
                Wire.encodeFor(WireMessage.Token("mains-own-token"), Wire.Direction.MAIN_TO_COMPANION), key,
            ),
        )
        assertFalse(PushMessageHandler.handle(ctx, reflected))
        assertEquals(before, s.sndiFcmToken)
    }

    // ---- Duplicates are copies, not look-alikes ----

    private fun relay(sender: String, body: String, dedupe: String, time: Long) = mapOf(
        PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(
            Wire.encodeFor(WireMessage.Relay(title = sender, body = body, dedupe = dedupe, time = time), Wire.Direction.COMPANION_TO_MAIN), key,
        ),
    )

    @Test
    fun two_identical_sms_are_both_kept_but_a_resent_copy_is_not() {
        val sender = uniq("+1555")
        val now = System.currentTimeMillis()
        // Same sender, text and sent second - the content key matches - but received apart.
        PushMessageHandler.handle(ctx, relay(sender, "OK", "same-key-$sender", now - 2_000))
        PushMessageHandler.handle(ctx, relay(sender, "OK", "same-key-$sender", now - 1_000))
        assertEquals(2, rowsFor(sender).size)
        // A repush of the first one: same key, same received time.
        PushMessageHandler.handle(ctx, relay(sender, "OK", "same-key-$sender", now - 2_000))
        assertEquals(2, rowsFor(sender).size)
        deleteConversation(sender)
    }

    // ---- Heartbeat off on the other phone ----

    @Test
    fun a_peer_with_heartbeat_off_is_not_reported_as_unreachable() {
        val s = Settings.get(ctx)
        val saved = Triple(s.heartbeatEnabled, s.lastPeerBeatAtMs, s.peerHeartbeatOff)
        try {
            s.heartbeatEnabled = true
            s.lastPeerBeatAtMs = 1L // long stale
            com.noti.logger.push.Heartbeat.onBeatReceived(ctx, request = false, off = true)
            assertTrue(s.peerHeartbeatOff)
            assertFalse(com.noti.logger.push.Heartbeat.isDisconnected(ctx))
            // Its next ordinary beat means it's back on.
            com.noti.logger.push.Heartbeat.onBeatReceived(ctx, request = false)
            assertFalse(s.peerHeartbeatOff)
        } finally {
            s.heartbeatEnabled = saved.first; s.lastPeerBeatAtMs = saved.second; s.peerHeartbeatOff = saved.third
        }
    }

    @Test
    fun notification_ids_follow_the_message_not_a_restarting_counter() {
        assertEquals(com.noti.logger.push.MessageNotifier.idFor(42), com.noti.logger.push.MessageNotifier.idFor(42))
        assertTrue(com.noti.logger.push.MessageNotifier.idFor(1) != com.noti.logger.push.MessageNotifier.idFor(2))
        assertTrue(com.noti.logger.push.MessageNotifier.idFor(1) >= 100_000)
    }
}
