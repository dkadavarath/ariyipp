package com.noti.logger

import com.noti.logger.redact.RawNotification
import com.noti.logger.redact.RedactedResult
import com.noti.logger.redact.RedactionConfig
import com.noti.logger.redact.RedactionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionRulesTest {

    private fun makeRaw(
        packageName: String = "com.example.app",
        title: String? = "Hello",
        text: String? = "World",
        bigText: String? = null,
        subText: String? = null,
        category: String? = "msg"
    ) = RawNotification(packageName, title, text, bigText, subText, category)

    // ---- Excluded package ----

    @Test
    fun `excluded package returns Dropped`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = setOf("com.evil.spam"),
                excludedKeywords = emptyList(),
                captureBody = true
            )
        )
        val result = rules.apply(makeRaw(packageName = "com.evil.spam"))
        assertEquals(RedactedResult.Dropped, result)
    }

    @Test
    fun `non-excluded package with captureBody true returns Kept with content`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = setOf("com.evil.spam"),
                excludedKeywords = emptyList(),
                captureBody = true
            )
        )
        val raw = makeRaw(title = "Hi", text = "There")
        val result = rules.apply(raw) as RedactedResult.Kept
        assertEquals("Hi", result.title)
        assertEquals("There", result.text)
    }

    // ---- Excluded keywords ----

    @Test
    fun `keyword in title (case-insensitive) returns Dropped`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = listOf("PASSWORD"),
                captureBody = true
            )
        )
        val result = rules.apply(makeRaw(title = "Enter password here"))
        assertEquals(RedactedResult.Dropped, result)
    }

    @Test
    fun `keyword in text (case-insensitive) returns Dropped`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = listOf("otp"),
                captureBody = true
            )
        )
        val result = rules.apply(makeRaw(text = "Your OTP is 123456"))
        assertEquals(RedactedResult.Dropped, result)
    }

    @Test
    fun `keyword in bigText returns Dropped`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = listOf("secret"),
                captureBody = true
            )
        )
        val result = rules.apply(makeRaw(bigText = "This is a SECRET message"))
        assertEquals(RedactedResult.Dropped, result)
    }

    @Test
    fun `keyword in subText returns Dropped`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = listOf("confidential"),
                captureBody = true
            )
        )
        val result = rules.apply(makeRaw(subText = "CONFIDENTIAL"))
        assertEquals(RedactedResult.Dropped, result)
    }

    @Test
    fun `keyword not present returns Kept`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = listOf("password"),
                captureBody = true
            )
        )
        val result = rules.apply(makeRaw(title = "Good morning", text = "How are you?"))
        assertTrue(result is RedactedResult.Kept)
    }

    // ---- captureBody = false ----

    @Test
    fun `captureBody false returns Kept with null bodies`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = emptyList(),
                captureBody = false
            )
        )
        val result = rules.apply(makeRaw(title = "Hello", text = "World", bigText = "Big", subText = "Sub")) as RedactedResult.Kept
        assertNull(result.title)
        assertNull(result.text)
        assertNull(result.bigText)
        assertNull(result.subText)
    }

    @Test
    fun `captureBody false metadata-only — package and category still available upstream`() {
        // RedactedResult.Kept carries only body fields; package/category come from RawNotification.
        // Verify that a Kept result is returned (not Dropped) so the caller can still log metadata.
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = emptyList(),
                captureBody = false
            )
        )
        val raw = makeRaw(packageName = "com.example.app", category = "msg")
        val result = rules.apply(raw)
        assertTrue(result is RedactedResult.Kept)
        // The caller preserves raw.packageName and raw.category independently.
        assertEquals("com.example.app", raw.packageName)
        assertEquals("msg", raw.category)
    }

    // ---- Normal pass-through ----

    @Test
    fun `normal notification with all fields is Kept unchanged`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = emptyList(),
                captureBody = true
            )
        )
        val raw = makeRaw(title = "T", text = "X", bigText = "B", subText = "S")
        val result = rules.apply(raw) as RedactedResult.Kept
        assertEquals("T", result.title)
        assertEquals("X", result.text)
        assertEquals("B", result.bigText)
        assertEquals("S", result.subText)
    }

    @Test
    fun `null fields in raw are preserved as null in Kept`() {
        val rules = RedactionRules(
            RedactionConfig(
                excludedPackages = emptySet(),
                excludedKeywords = emptyList(),
                captureBody = true
            )
        )
        val raw = makeRaw(title = null, text = null, bigText = null, subText = null)
        val result = rules.apply(raw) as RedactedResult.Kept
        assertNull(result.title)
        assertNull(result.text)
        assertNull(result.bigText)
        assertNull(result.subText)
    }
}
