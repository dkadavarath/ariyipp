package com.noti.logger.redact

import kotlinx.serialization.Serializable

/**
 * Per-app capture rule.
 *
 * [keywords] is an *include* filter: when non-empty, only notifications mentioning at least one of
 * the keywords are captured. Empty ⇒ capture everything from the app.
 * [notes] is user-authored text appended to the captured notification's text field.
 */
@Serializable
data class AppRule(
    val keywords: List<String> = emptyList(),
    val notes: String = ""
) {
    /** A rule that would change nothing - not worth persisting. */
    val isEmpty: Boolean get() = keywords.isEmpty() && notes.isBlank()
}

data class RedactionConfig(
    /** Allowlist: when non-empty, only these packages are captured. Empty ⇒ capture all apps. */
    val includedPackages: Set<String>,
    val excludedKeywords: List<String>,
    val captureBody: Boolean,
    /** Per-package keyword filters and notes, keyed by package name. */
    val appRules: Map<String, AppRule> = emptyMap()
)

data class RawNotification(
    val packageName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val category: String?
)

sealed interface RedactedResult {
    object Dropped : RedactedResult
    data class Kept(
        val title: String?,
        val text: String?,
        val bigText: String?,
        val subText: String?
    ) : RedactedResult
}

class RedactionRules(private val config: RedactionConfig) {

    fun apply(raw: RawNotification): RedactedResult {
        // Allowlist: empty ⇒ no package filter; non-empty ⇒ drop anything not listed.
        if (config.includedPackages.isNotEmpty() && raw.packageName !in config.includedPackages) {
            return RedactedResult.Dropped
        }

        val textFields = listOf(raw.title, raw.text, raw.bigText, raw.subText)
        for (keyword in config.excludedKeywords) {
            for (field in textFields) {
                if (field != null && field.contains(keyword, ignoreCase = true)) {
                    return RedactedResult.Dropped
                }
            }
        }

        val rule = config.appRules[raw.packageName]

        // Per-app include filter. Matched against the RAW fields so it still works in
        // metadata-only mode, where the bodies are dropped only after this point.
        if (rule != null && rule.keywords.isNotEmpty() && !containsAny(textFields, rule.keywords)) {
            return RedactedResult.Dropped
        }

        val notes = rule?.notes.orEmpty()
        return if (!config.captureBody) {
            // Notes are user-authored, not notification content, so they survive metadata-only mode.
            RedactedResult.Kept(
                title = null,
                text = appendNotes(null, notes),
                bigText = null,
                subText = null
            )
        } else {
            RedactedResult.Kept(
                title = raw.title,
                text = appendNotes(raw.text, notes),
                bigText = raw.bigText,
                subText = raw.subText
            )
        }
    }

    private fun containsAny(fields: List<String?>, keywords: List<String>): Boolean {
        for (keyword in keywords) {
            for (field in fields) {
                if (field != null && field.contains(keyword, ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun appendNotes(text: String?, notes: String): String? {
        if (notes.isBlank()) return text
        return if (text.isNullOrBlank()) notes else "$text\n$notes"
    }
}
