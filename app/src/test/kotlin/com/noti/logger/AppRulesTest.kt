package com.noti.logger

import com.noti.logger.config.AppRules
import com.noti.logger.redact.AppRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRulesTest {

    // ---- Keyword input parsing ----

    @Test
    fun `parses comma-separated keywords and trims whitespace`() {
        assertEquals(
            listOf("delivered", "out for delivery", "shipped"),
            AppRules.parseKeywords(" delivered ,out for delivery,  shipped ")
        )
    }

    @Test
    fun `blank input yields no keywords`() {
        assertTrue(AppRules.parseKeywords("").isEmpty())
        assertTrue(AppRules.parseKeywords("   ").isEmpty())
    }

    @Test
    fun `empty entries between commas are skipped`() {
        assertEquals(listOf("a", "b"), AppRules.parseKeywords("a,,b,  ,"))
    }

    @Test
    fun `multi-word keywords survive parsing`() {
        assertEquals(listOf("out for delivery"), AppRules.parseKeywords("out for delivery"))
    }

    @Test
    fun `format then parse round-trips`() {
        val keywords = listOf("delivered", "out for delivery")
        assertEquals(keywords, AppRules.parseKeywords(AppRules.formatKeywords(keywords)))
    }

    // ---- Storage round-trip ----

    @Test
    fun `encode then decode round-trips rules`() {
        val rules = mapOf(
            "com.shop" to AppRule(keywords = listOf("delivered", "shipped"), notes = "shopping"),
            "com.bank" to AppRule(keywords = emptyList(), notes = "")
        )
        assertEquals(rules, AppRules.decode(AppRules.encode(rules)))
    }

    @Test
    fun `notes with commas and newlines survive the round-trip`() {
        val rules = mapOf("com.shop" to AppRule(notes = "work phone, personal\nline two"))
        assertEquals(rules, AppRules.decode(AppRules.encode(rules)))
    }

    @Test
    fun `blank stored value decodes to an empty map`() {
        assertTrue(AppRules.decode("").isEmpty())
        assertTrue(AppRules.decode("   ").isEmpty())
    }

    @Test
    fun `corrupt stored value decodes to an empty map instead of throwing`() {
        assertTrue(AppRules.decode("{not json").isEmpty())
        assertTrue(AppRules.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun `empty rules map round-trips`() {
        assertTrue(AppRules.decode(AppRules.encode(emptyMap())).isEmpty())
    }

    // ---- isEmpty ----

    @Test
    fun `isEmpty is true only with no keywords and blank notes`() {
        assertTrue(AppRule().isEmpty)
        assertTrue(AppRule(notes = "  ").isEmpty)
        assertTrue(!AppRule(keywords = listOf("a")).isEmpty)
        assertTrue(!AppRule(notes = "tag").isEmpty)
    }
}
