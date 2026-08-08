package com.noti.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.SmsCommandHandler
import com.noti.shared.MessageCrypto
import com.noti.shared.SendCommand
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    }

    @After
    fun teardown() {
        val s = SenderSettings.get(ctx)
        s.relayKey = ""
        s.acceptCommands = false
    }

    private fun payload(to: String, body: String, encKey: String = key): Map<String, String> {
        val plain = Json.encodeToString(SendCommand(to, body))
        return mapOf(SmsCommandHandler.PAYLOAD_KEY to MessageCrypto.encrypt(plain, encKey))
    }

    @Test
    fun parses_a_valid_command() {
        assertEquals(SendCommand("+123456", "hello"), SmsCommandHandler.parse(ctx, payload("+123456", "hello")))
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
}
