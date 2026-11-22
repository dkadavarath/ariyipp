package com.noti.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.sender.sms.SmsPartTally
import com.noti.sender.sms.SmsSender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A long SMS is acked once, for all its parts. */
@RunWith(AndroidJUnit4::class)
class SmsPartTallyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sent = SmsSender.ACTION_SMS_SENT

    @Before fun clear() {
        ctx.getSharedPreferences("sms_part_tally", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun sent_only_once_every_part_is() {
        assertFalse(SmsPartTally.record(ctx, sent, 7, 0, 3, ok = true))
        assertFalse(SmsPartTally.record(ctx, sent, 7, 2, 3, ok = true))
        assertFalse("a repeated callback isn't another part", SmsPartTally.record(ctx, sent, 7, 2, 3, ok = true))
        assertTrue(SmsPartTally.record(ctx, sent, 7, 1, 3, ok = true))
    }

    @Test fun a_failed_part_decides_at_once_and_only_once() {
        assertTrue(SmsPartTally.record(ctx, sent, 8, 1, 3, ok = false))
        assertFalse("the message was already reported", SmsPartTally.record(ctx, sent, 8, 0, 3, ok = true))
        assertFalse(SmsPartTally.record(ctx, sent, 8, 2, 3, ok = true))
    }

    @Test fun messages_are_counted_apart() {
        assertFalse(SmsPartTally.record(ctx, sent, 9, 0, 2, ok = true))
        assertFalse(SmsPartTally.record(ctx, sent, 10, 0, 2, ok = true))
        assertTrue(SmsPartTally.record(ctx, sent, 9, 1, 2, ok = true))
    }
}
