package com.noti.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.sender.config.SenderSettings
import com.noti.sender.sms.WebhookConfigHandler
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
class WebhookConfigHandlerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = MessageCrypto.generateKeyBase64()

    @Before fun setup() {
        val s = SenderSettings.get(ctx)
        s.relayKey = key
        s.acceptRemoteConfig = true
    }

    @After fun teardown() {
        val s = SenderSettings.get(ctx)
        s.relayKey = ""
        s.acceptRemoteConfig = true
    }

    private fun payload(cfg: WireMessage, encKey: String = key): Map<String, String> =
        mapOf(WebhookConfigHandler.PAYLOAD_KEY to MessageCrypto.encrypt(Wire.encode(cfg), encKey))

    @Test fun parses_a_valid_config() {
        val cfg = WireMessage.WebhookConfig(enabled = true, url = "https://n8n.example/hook", authHeaderName = "key", authToken = "t")
        assertEquals(cfg, WebhookConfigHandler.parse(ctx, payload(cfg)))
    }

    @Test fun kill_switch_off_returns_null() {
        SenderSettings.get(ctx).acceptRemoteConfig = false
        assertNull(WebhookConfigHandler.parse(ctx, payload(WireMessage.WebhookConfig(url = "https://x"))))
    }

    @Test fun wrong_key_returns_null() {
        assertNull(WebhookConfigHandler.parse(ctx, payload(WireMessage.WebhookConfig(url = "https://x"), MessageCrypto.generateKeyBase64())))
    }

    @Test fun a_command_is_not_a_config() {
        assertNull(WebhookConfigHandler.parse(ctx, payload(WireMessage.Command(to = "+1", body = "hi"))))
    }
}
