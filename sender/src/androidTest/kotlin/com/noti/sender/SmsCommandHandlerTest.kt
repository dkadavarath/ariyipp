package com.noti.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsCommandHandler
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsCommandHandlerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = MessageCrypto.generateKeyBase64()

    @Before
    fun setup() {
        val s = SenderSettings.get(ctx)
        s.relayKey = key
        s.acceptCommands = true
        s.lastCommandMsgId = -1L
    }

    @After
    fun teardown() {
        val s = SenderSettings.get(ctx)
        s.relayKey = ""
        s.acceptCommands = false
        s.lastCommandMsgId = -1L
    }

    private fun payload(
        to: String,
        body: String,
        encKey: String = key,
        msgId: Long = 1L,
        issuedAt: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val plain = Wire.encode(WireMessage.Command(to = to, body = body, msgId = msgId, issuedAt = issuedAt))
        return mapOf(SmsCommandHandler.PAYLOAD_KEY to MessageCrypto.encrypt(plain, encKey))
    }

    @Test
    fun parses_a_valid_command() {
        val issuedAt = System.currentTimeMillis()
        assertEquals(
            WireMessage.Command("+123456", "hello", msgId = 1L, issuedAt = issuedAt),
            SmsCommandHandler.parse(ctx, payload("+123456", "hello", msgId = 1L, issuedAt = issuedAt)),
        )
    }

    @Test
    fun disabled_returns_null() {
        SenderSettings.get(ctx).acceptCommands = false
        assertNull(SmsCommandHandler.parse(ctx, payload("+1", "x")))
    }

    @Test
    fun wrong_key_returns_null() {
        assertNull(SmsCommandHandler.parse(ctx, payload("+1", "x", MessageCrypto.generateKeyBase64())))
    }

    @Test
    fun missing_payload_returns_null() {
        assertNull(SmsCommandHandler.parse(ctx, emptyMap()))
    }

    @Test
    fun blank_recipient_returns_null() {
        assertNull(SmsCommandHandler.parse(ctx, payload("", "x")))
    }

    @Test
    fun missing_msgId_returns_null() {
        assertNull(SmsCommandHandler.parse(ctx, payload("+1", "x", msgId = 0L)))
    }

    @Test
    fun replayed_msgId_returns_null_the_second_time() {
        val p = payload("+1", "x", msgId = 5L)
        assertEquals("+1", SmsCommandHandler.parse(ctx, p)?.to)
        assertNull(SmsCommandHandler.parse(ctx, p))
    }

    @Test
    fun lower_msgId_than_high_water_mark_returns_null() {
        SenderSettings.get(ctx).lastCommandMsgId = 10L
        assertNull(SmsCommandHandler.parse(ctx, payload("+1", "x", msgId = 10L)))
        assertNull(SmsCommandHandler.parse(ctx, payload("+1", "x", msgId = 9L)))
    }

    @Test
    fun stale_issuedAt_returns_null() {
        val old = System.currentTimeMillis() - 11 * 60_000L
        assertNull(SmsCommandHandler.parse(ctx, payload("+1", "x", issuedAt = old)))
    }

    @Test
    fun future_issuedAt_returns_null() {
        val future = System.currentTimeMillis() + 3 * 60_000L
        assertNull(SmsCommandHandler.parse(ctx, payload("+1", "x", issuedAt = future)))
    }
}
