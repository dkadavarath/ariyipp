package com.noti.logger

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.data.NotiDatabase
import com.noti.logger.push.NotificationActionReceiver
import com.noti.logger.push.ReplySignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A notification's reply action replies only to the conversation it was posted for. */
@RunWith(AndroidJUnit4::class)
class ReplySignatureTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun the_genuine_reply_verifies_and_a_changed_one_does_not() {
        val sig = ReplySignature.sign(ctx, "+15550001111", 100_042)
        assertTrue(ReplySignature.verify(ctx, "+15550001111", 100_042, sig))
        assertFalse("another recipient", ReplySignature.verify(ctx, "+15559999999", 100_042, sig))
        assertFalse("another notification", ReplySignature.verify(ctx, "+15550001111", 100_043, sig))
        assertFalse("no signature", ReplySignature.verify(ctx, "+15550001111", 100_042, null))
        assertFalse("garbage", ReplySignature.verify(ctx, "+15550001111", 100_042, "not-base64!"))
    }

    @Test fun a_reply_with_a_swapped_recipient_sends_nothing() {
        val dao = NotiDatabase.get(ctx).relayedMessageDao()
        val target = "+15558887777"
        dao.deleteConversation(target)
        // What a notification-access app could do with the mutable reply intent: keep the
        // signature, change the recipient.
        val forged = Intent(ctx, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_REPLY)
            .putExtra(NotificationActionReceiver.EXTRA_SENDER, target)
            .putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, 100_042)
            .putExtra(NotificationActionReceiver.EXTRA_SIG, ReplySignature.sign(ctx, "+15550001111", 100_042))
        NotificationActionReceiver().onReceive(ctx, forged)
        assertEquals("no outgoing message recorded (nor sent)", 0, dao.allMessages().count { it.sender == target })
    }
}
