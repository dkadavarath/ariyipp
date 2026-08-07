package com.noti.logger

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.push.MessageNotifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that a decrypted message actually renders as a notification. The emulator is
 * API 30, so POST_NOTIFICATIONS isn't required and posts go through directly.
 */
@RunWith(AndroidJUnit4::class)
class MessageNotifierTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    @After
    fun clear() = nm.cancelAll()

    @Test
    fun shows_a_notification_with_the_given_title_and_body() {
        val id = MessageNotifier.show(ctx, "Bank", "Your OTP is 123456")
        val posted = nm.activeNotifications.firstOrNull { it.id == id }
        assertNotNull("expected a posted notification with id $id", posted)
        val extras = posted!!.notification.extras
        assertEquals("Bank", extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Your OTP is 123456", extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun creates_the_relayed_messages_channel() {
        MessageNotifier.show(ctx, "t", "b")
        assertNotNull(nm.getNotificationChannel("noti_relayed"))
    }

    @Test
    fun distinct_messages_get_distinct_ids_so_they_stack() {
        val a = MessageNotifier.show(ctx, "t", "one")
        val b = MessageNotifier.show(ctx, "t", "two")
        assertNotEquals(a, b)
        assertEquals(2, nm.activeNotifications.count { it.id == a || it.id == b })
    }
}
