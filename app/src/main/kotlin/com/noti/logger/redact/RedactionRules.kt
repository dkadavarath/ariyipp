package com.noti.logger.redact

data class RedactionConfig(
    /** Allowlist: when non-empty, only these packages are captured. Empty ⇒ capture all apps. */
    val includedPackages: Set<String>,
    val excludedKeywords: List<String>,
    val captureBody: Boolean
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

        return if (!config.captureBody) {
            RedactedResult.Kept(title = null, text = null, bigText = null, subText = null)
        } else {
            RedactedResult.Kept(
                title = raw.title,
                text = raw.text,
                bigText = raw.bigText,
                subText = raw.subText
            )
        }
    }
}
