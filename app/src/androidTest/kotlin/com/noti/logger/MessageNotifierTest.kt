package com.noti.logger

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noti.logger.push.MessageNotifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that a decrypted message renders as a notification. Notification state is async and
 * shared across tests, so each test starts from a polled-clean slate and polls for its own posts.
 */
@RunWith(AndroidJUnit4::class)
class MessageNotifierTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    @Before
    fun prep() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        clearAndWait()
    }

    @After
    fun clear() = clearAndWait()

    private fun clearAndWait() {
        nm.cancelAll()
        val deadline = System.currentTimeMillis() + 2000
        while (nm.activeNotifications.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    /** Polls (notify() → activeNotifications can lag) for a notification matching [predicate]. */
    private fun await(predicate: (android.service.notification.StatusBarNotification) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (nm.activeNotifications.any(predicate)) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun shows_a_notification_with_the_given_title_and_body() {
        val id = MessageNotifier.show(ctx, "Bank", "Your OTP is 123456")
        assertNotNull("expected a posted notification with id $id",
            await { it.id == id }.takeIf { it })
        val posted = nm.activeNotifications.first { it.id == id }
        val extras = posted.notification.extras
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

        var count = 0
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            count = nm.activeNotifications.count { it.id == a || it.id == b }
            if (count == 2) break
            Thread.sleep(50)
        }
        assertEquals(2, count)
    }
}
