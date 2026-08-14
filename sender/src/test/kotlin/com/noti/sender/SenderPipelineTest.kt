package com.noti.sender

import com.noti.sender.sms.CapturedSms
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderPipelineTest {

    @Test
    fun `encryptForFcm produces a payload noti can decrypt back to the message`() {
        val key = MessageCrypto.generateKeyBase64()
        val msg = WireMessage.Relay(title = "Bank", body = "Your OTP is 4567")

        val payload = SenderPipeline.encryptForFcm(msg, key)
        val plain = MessageCrypto.decrypt(payload, key)
        val decoded = Wire.decode(plain)

        assertEquals(msg, decoded)
    }

    @Test
    fun `fcm message shows sender on the sim name as the title`() {
        val sms = CapturedSms("+971500000000", "Test6", 1L, 2L, "e&")
        val m = SenderPipeline.fcmMessage(sms)
        assertEquals("+971500000000 on e&", m.title)
        assertEquals("Test6", m.body)
    }

    @Test
    fun `fcm title omits the on-suffix when the sim is blank`() {
        val m = SenderPipeline.fcmMessage(CapturedSms("+123", "hi", 1L, 2L, ""))
        assertEquals("+123", m.title)
    }

    @Test
    fun `n8n text uses the From-Message-Sent-Received-Sim block`() {
        val sms = CapturedSms("+971500000000", "Test6", 1786172225000L, 1786172227087L, "e&")
        assertEquals(
            "From: +971500000000\n" +
                "Message: Test6\n" +
                "Sent: 1786172225000\n" +
                "Received: 1786172227087\n" +
                "Sim: e&",
            SenderPipeline.n8nText(sms)
        )
    }

    @Test
    fun `smsToUploadItem carries the block in the text field and maps the rest`() {
        val sms = CapturedSms("+971500000000", "Test6", 1786172225000L, 1786172227087L, "sim1")
        val item = SenderPipeline.smsToUploadItem(sms, "dev-1")

        assertEquals(SenderPipeline.n8nText(sms), item.text)
        assertEquals("+971500000000", item.title)
        assertEquals("sms", item.pkg)
        assertEquals("sms", item.category)
        assertEquals("SMS", item.appLabel)
        assertEquals("dev-1", item.deviceId)
        assertTrue(item.uid.startsWith("dev-1|sms|"))
    }
}
