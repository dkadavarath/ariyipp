package com.noti.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcmLabelTest {

    private fun message(body: String) = Json.parseToJsonElement(body).jsonObject["message"]!!.jsonObject

    @Test
    fun label_is_sent_as_analytics_label() {
        val msg = message(FcmSender.requestBody("tok", mapOf("payload" to "x"), false, FcmLabel.HEARTBEAT_TO_MAIN))
        assertEquals(
            "heartbeat-c2m",
            msg["fcm_options"]!!.jsonObject["analytics_label"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun no_label_means_no_fcm_options() {
        assertNull(message(FcmSender.requestBody("tok", mapOf("payload" to "x"), false, null))["fcm_options"])
    }

    @Test
    fun invalid_label_is_dropped_not_sent() {
        assertNull(message(FcmSender.requestBody("tok", emptyMap(), false, "has spaces"))["fcm_options"])
        assertNull(message(FcmSender.requestBody("tok", emptyMap(), false, "x".repeat(51)))["fcm_options"])
    }

    @Test
    fun every_label_is_valid() {
        val labels = listOf(
            FcmLabel.HEARTBEAT_TO_COMPANION, FcmLabel.HEARTBEAT_TO_MAIN, FcmLabel.COMMAND,
            FcmLabel.STATUS_REQUEST, FcmLabel.CONFIG, FcmLabel.TOKEN_TO_COMPANION,
            FcmLabel.TOKEN_TO_MAIN, FcmLabel.RELAY, FcmLabel.ACK, FcmLabel.STATUS_REPORT,
        )
        labels.forEach { l ->
            val msg = message(FcmSender.requestBody("tok", emptyMap(), false, l))
            assertEquals(l, msg["fcm_options"]!!.jsonObject["analytics_label"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun message_id_is_the_part_after_messages() {
        assertEquals("0:1500415314455276%31bd1c96", FcmLabel.messageId("projects/p/messages/0:1500415314455276%31bd1c96"))
        assertEquals("", FcmLabel.messageId("UNREGISTERED"))
    }

    @Test
    fun sends_are_high_priority_unless_asked_otherwise() {
        fun prio(body: String) = message(body)["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content
        assertEquals("high", prio(FcmSender.requestBody("tok", emptyMap(), false, null)))
        assertEquals("normal", prio(FcmSender.requestBody("tok", emptyMap(), false, null, highPriority = false)))
    }

    @Test
    fun collapse_key_and_ttl_go_in_the_android_block() {
        val android = message(FcmSender.requestBody("tok", emptyMap(), false, null, highPriority = false,
            collapseKey = HeartbeatPolicy.COLLAPSE_KEY, ttlSeconds = 1800))["android"]!!.jsonObject
        assertEquals("heartbeat", android["collapse_key"]!!.jsonPrimitive.content)
        assertEquals("1800s", android["ttl"]!!.jsonPrimitive.content)
        assertEquals("normal", android["priority"]!!.jsonPrimitive.content)
    }

    @Test
    fun relays_stay_non_collapsible() {
        val android = message(FcmSender.requestBody("tok", emptyMap(), false, null))["android"]!!.jsonObject
        assertNull(android["collapse_key"])
        assertNull(android["ttl"])
    }

    @Test
    fun catch_up_round_trips_on_the_wire() {
        assertEquals(WireMessage.CatchUp(1234L), Wire.decode(Wire.encode(WireMessage.CatchUp(1234L))))
        assertEquals(WireMessage.DeliveryAck(5, 4, "late"), Wire.decode(Wire.encode(WireMessage.DeliveryAck(5, 4, "late"))))
    }
}
