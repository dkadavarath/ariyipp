package com.noti.logger.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noti.logger.redact.RedactionConfig
import java.io.File
import java.util.UUID

enum class TriggerMode { PERIODIC, THRESHOLD, MANUAL }

class Settings private constructor(private val prefs: SharedPreferences) {

    // ---- Webhook / auth ----

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()
        }

    var bearerToken: String
        get() = prefs.getString(KEY_BEARER_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_BEARER_TOKEN, value).apply()
        }

    /** Auth header name. Default "Authorization"; set to a custom header (e.g. "key") for n8n Header Auth. */
    var authHeaderName: String
        get() = prefs.getString(KEY_AUTH_HEADER_NAME, "Authorization") ?: "Authorization"
        set(value) {
            prefs.edit().putString(KEY_AUTH_HEADER_NAME, value).apply()
        }

    /** Value scheme prefixed to the token. Default "Bearer "; set to "" to send the raw token. */
    var authHeaderPrefix: String
        get() = prefs.getString(KEY_AUTH_HEADER_PREFIX, "Bearer ") ?: "Bearer "
        set(value) {
            prefs.edit().putString(KEY_AUTH_HEADER_PREFIX, value).apply()
        }

    /** Full auth header value actually sent on the wire: prefix + token. */
    fun authHeaderValue(): String = authHeaderPrefix + bearerToken

    /** Gzip the upload body. Default OFF — many webhook receivers don't decompress request bodies. */
    var gzipEnabled: Boolean
        get() = prefs.getBoolean(KEY_GZIP_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GZIP_ENABLED, value).apply()
        }

    /** Generated once and persisted; never changes for a given install. */
    val deviceId: String
        get() {
            val stored = prefs.getString(KEY_DEVICE_ID, null)
            if (stored != null) return stored
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            return newId
        }

    // ---- Trigger config ----

    var triggerMode: TriggerMode
        get() = try {
            TriggerMode.valueOf(
                prefs.getString(KEY_TRIGGER_MODE, TriggerMode.PERIODIC.name)
                    ?: TriggerMode.PERIODIC.name
            )
        } catch (e: IllegalArgumentException) {
            TriggerMode.PERIODIC
        }
        set(value) {
            prefs.edit().putString(KEY_TRIGGER_MODE, value.name).apply()
        }

    /** Minimum 15 minutes; values below 15 are silently raised. */
    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL_MINUTES, 15).coerceAtLeast(15)
        set(value) {
            prefs.edit().putInt(KEY_INTERVAL_MINUTES, value.coerceAtLeast(15)).apply()
        }

    var thresholdCount: Int
        get() = prefs.getInt(KEY_THRESHOLD_COUNT, 20)
        set(value) {
            prefs.edit().putInt(KEY_THRESHOLD_COUNT, value).apply()
        }

    // ---- Connectivity constraints ----

    var requireUnmetered: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_UNMETERED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REQUIRE_UNMETERED, value).apply()
        }

    var requireCharging: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_CHARGING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REQUIRE_CHARGING, value).apply()
        }

    // ---- Redaction / privacy ----

    var excludedPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, value).apply()
        }

    /** Newline-delimited so multi-word keywords (e.g. "verification code") survive round-trip. */
    var excludedKeywords: List<String>
        get() {
            val raw = prefs.getString(KEY_EXCLUDED_KEYWORDS, "") ?: ""
            return if (raw.isBlank()) emptyList()
            else raw.split(KEYWORD_DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
        }
        set(value) {
            prefs.edit()
                .putString(KEY_EXCLUDED_KEYWORDS, value.joinToString(KEYWORD_DELIMITER))
                .apply()
        }

    var captureBody: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_BODY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CAPTURE_BODY, value).apply()
        }

    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 30)
        set(value) {
            prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()
        }

    // ---- Status ----

    var lastUploadAtMs: Long
        get() = prefs.getLong(KEY_LAST_UPLOAD_AT_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_UPLOAD_AT_MS, value).apply()
        }

    var lastUploadResult: String?
        get() = prefs.getString(KEY_LAST_UPLOAD_RESULT, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_UPLOAD_RESULT, value).apply()
        }

    // ---- Derived ----

    fun redactionConfig(): RedactionConfig = RedactionConfig(
        excludedPackages = excludedPackages,
        excludedKeywords = excludedKeywords,
        captureBody = captureBody
    )

    // ---- Singleton ----

    companion object {
        private const val PREFS_NAME = "noti_settings"
        private const val KEYWORD_DELIMITER = "\n"

        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_BEARER_TOKEN = "bearer_token"
        private const val KEY_AUTH_HEADER_NAME = "auth_header_name"
        private const val KEY_AUTH_HEADER_PREFIX = "auth_header_prefix"
        private const val KEY_GZIP_ENABLED = "gzip_enabled"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TRIGGER_MODE = "trigger_mode"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_THRESHOLD_COUNT = "threshold_count"
        private const val KEY_REQUIRE_UNMETERED = "require_unmetered"
        private const val KEY_REQUIRE_CHARGING = "require_charging"
        private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
        private const val KEY_EXCLUDED_KEYWORDS = "excluded_keywords"
        private const val KEY_CAPTURE_BODY = "capture_body"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_LAST_UPLOAD_AT_MS = "last_upload_at_ms"
        private const val KEY_LAST_UPLOAD_RESULT = "last_upload_result"

        @Volatile
        private var INSTANCE: Settings? = null

        fun get(context: Context): Settings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Settings(openPrefs(context.applicationContext)).also { INSTANCE = it }
            }
        }

        private fun openPrefs(appContext: Context): SharedPreferences {
            return try {
                buildEncryptedPrefs(appContext)
            } catch (e: Exception) {
                // Corrupt or missing keyset — wipe and recreate.
                deletePrefsFile(appContext)
                buildEncryptedPrefs(appContext)
            }
        }

        private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun deletePrefsFile(appContext: Context) {
            val prefsDir = File(appContext.filesDir.parent ?: return, "shared_prefs")
            File(prefsDir, "$PREFS_NAME.xml").delete()
        }
    }
}
