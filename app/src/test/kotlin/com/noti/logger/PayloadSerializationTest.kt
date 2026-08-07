package com.noti.logger

import com.noti.shared.UploadBatch
import com.noti.shared.UploadItem
import com.noti.shared.epochMillisToIso
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadSerializationTest {

    private val json = Json { encodeDefaults = true }

    private fun sampleBatch(): UploadBatch = UploadBatch(
        batch = listOf(
            UploadItem(
                deviceId = "device-abc-123",
                uid = "uid-001",
                pkg = "com.example.app",
                appLabel = "Example App",
                postTime = "2023-11-14T22:13:20Z",
                title = "Hello",
                text = "World",
                bigText = "Big World",
                subText = "Sub",
                category = "msg"
            )
        )
    )

    // ---- Snake-case key presence ----

    @Test
    fun `serialized JSON contains device_id key`() {
        val serialized = json.encodeToString(sampleBatch())
        assertTrue("Expected device_id in JSON: $serialized", serialized.contains("\"device_id\""))
    }

    @Test
    fun `serialized JSON contains package key`() {
        val serialized = json.encodeToString(sampleBatch())
        assertTrue("Expected package in JSON: $serialized", serialized.contains("\"package\""))
    }

    @Test
    fun `serialized JSON contains big_text key`() {
        val serialized = json.encodeToString(sampleBatch())
        assertTrue("Expected big_text in JSON: $serialized", serialized.contains("\"big_text\""))
    }

    @Test
    fun `serialized JSON contains post_time key`() {
        val serialized = json.encodeToString(sampleBatch())
        assertTrue("Expected post_time in JSON: $serialized", serialized.contains("\"post_time\""))
    }

    @Test
    fun `post_time serializes as a quoted ISO-8601 datetime string`() {
        val serialized = json.encodeToString(sampleBatch())
        assertTrue(
            "Expected ISO datetime string for post_time: $serialized",
            serialized.contains("\"post_time\":\"2023-11-14T22:13:20Z\"")
        )
    }

    @Test
    fun `epochMillisToIso formats epoch millis as ISO-8601 UTC`() {
        assertEquals("2023-11-14T22:13:20Z", epochMillisToIso(1_700_000_000_000L))
        // Sub-second millis are preserved in the ISO output.
        assertTrue(epochMillisToIso(1_700_000_000_174L).endsWith(".174Z"))
    }

    @Test
    fun `serialized JSON contains app_label key`() {
        val serialized = json.encodeToString(sampleBatch())
        assertTrue("Expected app_label in JSON: $serialized", serialized.contains("\"app_label\""))
    }

    @Test
    fun `serialized JSON contains sub_text key`() {
        val serialized = json.encodeToString(sampleBatch())
        assertTrue("Expected sub_text in JSON: $serialized", serialized.contains("\"sub_text\""))
    }

    // ---- Round-trip ----

    @Test
    fun `round-trip encode then decode equals original`() {
        val original = sampleBatch()
        val serialized = json.encodeToString(original)
        val decoded = json.decodeFromString<UploadBatch>(serialized)
        assertEquals(original, decoded)
    }

    @Test
    fun `decoded item has correct device_id`() {
        val original = sampleBatch()
        val decoded = json.decodeFromString<UploadBatch>(json.encodeToString(original))
        assertEquals("device-abc-123", decoded.batch.first().deviceId)
    }

    @Test
    fun `decoded item has correct package name`() {
        val original = sampleBatch()
        val decoded = json.decodeFromString<UploadBatch>(json.encodeToString(original))
        assertEquals("com.example.app", decoded.batch.first().pkg)
    }

    @Test
    fun `null optional fields serialize and round-trip correctly`() {
        val batch = UploadBatch(
            batch = listOf(
                UploadItem(
                    deviceId = "dev",
                    uid = "u1",
                    pkg = "com.test",
                    appLabel = null,
                    postTime = "1970-01-01T00:00:00Z",
                    title = null,
                    text = null,
                    bigText = null,
                    subText = null,
                    category = null
                )
            )
        )
        val decoded = json.decodeFromString<UploadBatch>(json.encodeToString(batch))
        assertEquals(batch, decoded)
    }
}
