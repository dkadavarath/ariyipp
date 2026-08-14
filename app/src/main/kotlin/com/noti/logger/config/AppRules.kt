package com.noti.logger.config

import com.noti.logger.redact.AppRule
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Storage format and user-input parsing for the per-app rules map held in [Settings].
 * Kept pure (no Android types) so it is unit-testable.
 */
object AppRules {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), AppRule.serializer())

    /** Splits the user's comma-separated input into trimmed, non-empty keywords. */
    fun parseKeywords(input: String): List<String> =
        input.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Renders keywords back into the comma-separated form shown in the editor. */
    fun formatKeywords(keywords: List<String>): String = keywords.joinToString(", ")

    fun encode(rules: Map<String, AppRule>): String = json.encodeToString(mapSerializer, rules)

    /** Blank or corrupt input yields an empty map - the capture path must never throw here. */
    fun decode(raw: String): Map<String, AppRule> =
        if (raw.isBlank()) emptyMap()
        else try {
            json.decodeFromString(mapSerializer, raw)
        } catch (e: Exception) {
            emptyMap()
        }
}
