package com.noti.sender

import com.noti.shared.MessageCrypto
import com.noti.shared.RelayMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderPipelineTest {

    @Test
    fun `encryptForFcm produces a payload noti can decrypt back to the message`() {
        val key = MessageCrypto.generateKeyBase64()
        val msg = RelayMessage("Bank", "Your OTP is 4567")

        val payload = SenderPipeline.encryptForFcm(msg, key)
        // Mirror the receiver: decrypt then parse.
        val plain = MessageCrypto.decrypt(payload, key)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<RelayMessage>(plain)

        assertEquals(msg, decoded)
    }

    @Test
    fun `smsToUploadItem maps an SMS into noti's schema`() {
        val item = SenderPipeline.smsToUploadItem(RelayMessage("+123456", "hi"), "dev-1", 1_700_000_000_000L)

        assertEquals("+123456", item.title)
        assertEquals("hi", item.text)
        assertEquals("sms", item.pkg)
        assertEquals("sms", item.category)
        assertEquals("SMS", item.appLabel)
        assertEquals("dev-1", item.deviceId)
        assertEquals("2023-11-14T22:13:20Z", item.postTime)
        assertTrue(item.uid.startsWith("dev-1|sms|"))
    }
}
