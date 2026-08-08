package com.noti.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun `round-trips token and key`() {
        val token = "efEvGqptQoK:APA91bHsvqZ-abc_123"
        val key = "Mpm+/TP95CdtK1CfBPdDrLNnlZzh27zESZseSfchEV4="
        val (t, k) = PairingPayload.parse(PairingPayload.format(token, key))!!
        assertEquals(token, t)
        assertEquals(key, k)
    }

    @Test
    fun `key containing base64 specials survives`() {
        val (_, k) = PairingPayload.parse(PairingPayload.format("tok", "a+b/c=="))!!
        assertEquals("a+b/c==", k)
    }

    @Test
    fun `wrong prefix is rejected`() {
        assertNull(PairingPayload.parse("random text"))
        assertNull(PairingPayload.parse("noti-pair:v2:tok|key"))
    }

    @Test
    fun `missing separator or empty halves are rejected`() {
        assertNull(PairingPayload.parse("noti-pair:v1:tokenonly"))
        assertNull(PairingPayload.parse("noti-pair:v1:|key"))
        assertNull(PairingPayload.parse("noti-pair:v1:tok|"))
    }

    @Test
    fun `reverse round-trips token and key`() {
        val token = "arYtok:FCMreverse_987"
        val key = "Mpm+/TP95CdtK1CfBPdDrLNnlZzh27zESZseSfchEV4="
        val (t, k) = PairingPayload.parseReverse(PairingPayload.formatReverse(token, key))!!
        assertEquals(token, t)
        assertEquals(key, k)
    }

    @Test
    fun `forward and reverse payloads are not interchangeable`() {
        val fwd = PairingPayload.format("tok", "key")
        val rev = PairingPayload.formatReverse("tok", "key")
        assertNull(PairingPayload.parseReverse(fwd)) // forward can't be read as reverse
        assertNull(PairingPayload.parse(rev))        // and vice versa
    }
}
