package com.noti.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.HeartbeatHandler
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
class HeartbeatHandlerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = MessageCrypto.generateKeyBase64()

    @Before fun setup() {
        SenderSettings.get(ctx).relayKey = key
    }

    @After fun teardown() {
        SenderSettings.get(ctx).relayKey = ""
    }

    private fun payload(msg: WireMessage, encKey: String = key): Map<String, String> =
        mapOf(HeartbeatHandler.PAYLOAD_KEY to MessageCrypto.encrypt(Wire.encode(msg), encKey))

    @Test fun parses_a_plain_beat() {
        val hb = WireMessage.Heartbeat(request = false)
        assertEquals(hb, HeartbeatHandler.parse(ctx, payload(hb)))
    }

    @Test fun parses_a_force_check_beat() {
        val hb = WireMessage.Heartbeat(request = true)
        assertEquals(hb, HeartbeatHandler.parse(ctx, payload(hb)))
    }

    @Test fun wrong_key_returns_null() {
        assertNull(HeartbeatHandler.parse(ctx, payload(WireMessage.Heartbeat(), MessageCrypto.generateKeyBase64())))
    }

    @Test fun a_command_is_not_a_heartbeat() {
        assertNull(HeartbeatHandler.parse(ctx, payload(WireMessage.Command(to = "+1", body = "hi"))))
    }
}
