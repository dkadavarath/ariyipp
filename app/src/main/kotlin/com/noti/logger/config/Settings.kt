package com.noti.logger.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noti.logger.redact.AppRule
import com.noti.logger.redact.RedactionConfig
import java.io.File
import java.util.UUID

enum class TriggerMode { PERIODIC, THRESHOLD, MANUAL }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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

    /** Generated once and persisted; never changes for a given install. Memoized so the capture
     *  path doesn't decrypt it on every notification. */
    @Volatile
    private var cachedDeviceId: String? = null
    val deviceId: String
        get() {
            cachedDeviceId?.let { return it }
            synchronized(this) {
                cachedDeviceId?.let { return it }
                val id = prefs.getString(KEY_DEVICE_ID, null)
                    ?: UUID.randomUUID().toString().also {
                        prefs.edit().putString(KEY_DEVICE_ID, it).apply()
                    }
                cachedDeviceId = id
                return id
            }
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

    /** Allowlist of package names to capture. Empty ⇒ capture all apps. */
    var includedPackages: Set<String>
        get() = prefs.getStringSet(KEY_INCLUDED_PACKAGES, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_INCLUDED_PACKAGES, value).apply()
            cachedRedaction = null
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
            cachedRedaction = null
        }

    /**
     * Per-app keyword filters and notes, keyed by package name. Only consulted for packages that
     * pass [includedPackages], so entries for de-selected apps are inert (and are kept, so a
     * re-selected app gets its rule back).
     */
    var appRules: Map<String, AppRule>
        get() = AppRules.decode(prefs.getString(KEY_APP_RULES, "") ?: "")
        set(value) {
            prefs.edit().putString(KEY_APP_RULES, AppRules.encode(value)).apply()
            cachedRedaction = null
        }

    var captureBody: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_BODY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CAPTURE_BODY, value).apply()
            cachedRedaction = null
        }

    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 30)
        set(value) {
            prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()
        }

    /** Duplicate-suppression window in seconds. Identical content from the same app within this
     *  window is dropped. Default 1 day; 0 disables dedup. */
    var dedupeWindowSeconds: Int
        get() = prefs.getInt(KEY_DEDUPE_WINDOW_SECONDS, 86_400)
        set(value) {
            prefs.edit().putInt(KEY_DEDUPE_WINDOW_SECONDS, value.coerceAtLeast(0)).apply()
        }

    // ---- Inbound push (FCM relay receiver) ----

    /** Opt-in: show notifications pushed from the sender app. Default off. */
    var pushInboundEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSH_INBOUND_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PUSH_INBOUND_ENABLED, value).apply()
        }

    /** Add an in-app "Copy" action to a relayed-message notification when it contains a one-time code. */
    var otpCopyEnabled: Boolean
        get() = prefs.getBoolean(KEY_OTP_COPY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_OTP_COPY, value).apply()
        }

    /** Ask the OS not to add its own auto-generated actions (e.g. its OTP-copy chip) to our
     *  notifications — leaving only the app's own actions. Default off. */
    var suppressSystemNotifActions: Boolean
        get() = prefs.getBoolean(KEY_SUPPRESS_SYS_ACTIONS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SUPPRESS_SYS_ACTIONS, value).apply()
        }

    /** Pre-shared AES-256-GCM key (base64) for decrypting inbound relayed messages. */
    var relayKey: String
        get() = prefs.getString(KEY_RELAY_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_RELAY_KEY, value.trim()).apply()
        }

    /** This device's FCM registration token; copied to the sender app at pairing. Updated on refresh. */
    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FCM_TOKEN, value).apply()
        }

    /** Service-account key (JSON) so noti can PUSH send-SMS commands to sndi (Phase B, reverse send). */
    var serviceAccountJson: String
        get() = prefs.getString(KEY_SA_JSON, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SA_JSON, value).apply()
        }

    /** sndi's FCM registration token — the target for send-SMS commands. */
    var sndiFcmToken: String
        get() = prefs.getString(KEY_SNDI_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SNDI_TOKEN, value.trim()).apply()
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

    // ---- Appearance ----

    var themeMode: ThemeMode
        get() = try {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    /** false = Default (blue brand theme); true = Material You dynamic color (Android 12+). */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        }

    /** Pure-black (AMOLED) surfaces when dark mode is active. No effect in light mode. */
    var amoled: Boolean
        get() = prefs.getBoolean(KEY_AMOLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AMOLED, value).apply()
        }

    // ---- Muted conversations ----

    /** Senders whose new messages are stored (and shown as unread) but don't raise a notification. */
    var mutedSenders: Set<String>
        get() = prefs.getStringSet(KEY_MUTED_SENDERS, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_MUTED_SENDERS, value).apply()
        }

    fun isMuted(sender: String): Boolean = mutedSenders.contains(sender)

    fun setMuted(sender: String, muted: Boolean) {
        val next = mutedSenders.toMutableSet()
        if (muted) next.add(sender) else next.remove(sender)
        mutedSenders = next
    }

    // ---- Derived ----

    // Cached snapshot; invalidated whenever includedPackages / excludedKeywords / captureBody /
    // appRules change. Avoids 4 decrypts + a keyword split + a JSON parse on every notification.
    @Volatile
    private var cachedRedaction: RedactionConfig? = null

    fun redactionConfig(): RedactionConfig {
        cachedRedaction?.let { return it }
        return RedactionConfig(
            includedPackages = includedPackages,
            excludedKeywords = excludedKeywords,
            captureBody = captureBody,
            appRules = appRules
        ).also { cachedRedaction = it }
    }

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
        private const val KEY_INCLUDED_PACKAGES = "included_packages"
        private const val KEY_EXCLUDED_KEYWORDS = "excluded_keywords"
        private const val KEY_APP_RULES = "app_rules"
        private const val KEY_CAPTURE_BODY = "capture_body"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_DEDUPE_WINDOW_SECONDS = "dedupe_window_seconds"
        private const val KEY_PUSH_INBOUND_ENABLED = "push_inbound_enabled"
        private const val KEY_OTP_COPY = "otp_copy_enabled"
        private const val KEY_SUPPRESS_SYS_ACTIONS = "suppress_system_notif_actions"
        private const val KEY_RELAY_KEY = "relay_key"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_SA_JSON = "sa_json"
        private const val KEY_SNDI_TOKEN = "sndi_token"
        private const val KEY_LAST_UPLOAD_AT_MS = "last_upload_at_ms"
        private const val KEY_LAST_UPLOAD_RESULT = "last_upload_result"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_MUTED_SENDERS = "muted_senders"

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
