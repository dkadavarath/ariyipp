package com.noti.logger.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noti.logger.redact.AppRule
import com.noti.logger.reminder.ReminderPolicy
import com.noti.shared.DeviceStatusPolicy
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

    /** Gzip the upload body. Default OFF - many webhook receivers don't decompress request bodies. */
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

    /** How long captured notification rows are kept. Notifications only - chat history has its
     *  own [chatRetentionDays]. */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 30)
        set(value) {
            prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()
        }

    /** How long relayed chat history is kept, in days; 0 (the default) keeps it forever. See
     *  [com.noti.logger.data.ChatRetention]. */
    var chatRetentionDays: Int
        get() = prefs.getInt(KEY_CHAT_RETENTION_DAYS, 0)
        set(value) {
            prefs.edit().putInt(KEY_CHAT_RETENTION_DAYS, value.coerceAtLeast(0)).apply()
        }

    /** Duplicate-suppression window in seconds. Identical content from the same app within this
     *  window is dropped. Default 1 day; 0 disables dedup. */
    var dedupeWindowSeconds: Int
        get() = prefs.getInt(KEY_DEDUPE_WINDOW_SECONDS, 86_400)
        set(value) {
            prefs.edit().putInt(KEY_DEDUPE_WINDOW_SECONDS, value.coerceAtLeast(0)).apply()
        }

    // ---- Role (ariyipp: hub vs SMS companion) ----

    /** Which role this device plays. null until the user picks one at onboarding. */
    var role: com.noti.shared.Role?
        get() = prefs.getString(KEY_ROLE, null)?.let { runCatching { com.noti.shared.Role.valueOf(it) }.getOrNull() }
        set(value) {
            prefs.edit().putString(KEY_ROLE, value?.name).apply()
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
     *  notifications - leaving only the app's own actions. Default off. */
    var suppressSystemNotifActions: Boolean
        get() = prefs.getBoolean(KEY_SUPPRESS_SYS_ACTIONS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SUPPRESS_SYS_ACTIONS, value).apply()
        }

    /** Pre-shared AES-256-GCM key (base64) for decrypting inbound relayed messages. Memoized like
     *  [deviceId] - it's read on every inbound push, and EncryptedSharedPreferences decrypts per read. */
    @Volatile
    private var cachedRelayKey: String? = null
    var relayKey: String
        get() {
            cachedRelayKey?.let { return it }
            synchronized(this) {
                cachedRelayKey?.let { return it }
                return (prefs.getString(KEY_RELAY_KEY, "") ?: "").also { cachedRelayKey = it }
            }
        }
        set(value) {
            val trimmed = value.trim()
            prefs.edit().putString(KEY_RELAY_KEY, trimmed).apply()
            cachedRelayKey = trimmed
        }

    /** This device's FCM registration token; copied to the sender app at pairing. Updated on refresh. */
    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FCM_TOKEN, value).apply()
        }

    /** Last token of ours the companion is known to have (set after a successful announce), so the
     *  process-start announce only fires when the token actually changed. */
    var announcedToken: String
        get() = prefs.getString(KEY_ANNOUNCED_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_ANNOUNCED_TOKEN, value).apply()
        }

    /** Service-account key (JSON) so noti can PUSH send-SMS commands to sndi (Phase B, reverse send).
     *  Memoized - it's a multi-KB blob re-read on every heartbeat/command send. */
    @Volatile
    private var cachedServiceAccountJson: String? = null
    var serviceAccountJson: String
        get() {
            cachedServiceAccountJson?.let { return it }
            synchronized(this) {
                cachedServiceAccountJson?.let { return it }
                return (prefs.getString(KEY_SA_JSON, "") ?: "").also { cachedServiceAccountJson = it }
            }
        }
        set(value) {
            prefs.edit().putString(KEY_SA_JSON, value).apply()
            cachedServiceAccountJson = value
        }

    /** Imported `google-services.json` contents, used to init Firebase (FCM) at runtime instead of
     *  at build time — see [com.noti.logger.push.FirebaseInit]. */
    var firebaseConfigJson: String
        get() = prefs.getString(KEY_FIREBASE_CONFIG_JSON, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FIREBASE_CONFIG_JSON, value).apply()
        }

    /** sndi's FCM registration token - the target for send-SMS commands. */
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

    /** The database encryption key (base64, 32 bytes; see DbEncryption). Blank until first use. */
    val dbKey: String
        get() = prefs.getString(KEY_DB_KEY, "") ?: ""

    /** Stores [dbKey] with a synchronous commit: the database is encrypted with it straight after,
     *  and a key lost to a process death before an async write landed would lose the data too. */
    var dbKeySync: String
        get() = dbKey
        set(value) { check(prefs.edit().putString(KEY_DB_KEY, value).commit()) { "couldn't store the database key" } }

    /** Screenshot protection (see com.noti.sender.ui.ScreenGuard): pairing, key and backup screens
     *  (on by default) and chats, messages and reminders (off by default). */
    var protectKeyScreens: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_KEYS, true)
        set(value) { prefs.edit().putBoolean(KEY_PROTECT_KEYS, value).apply() }

    var protectMessages: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_MESSAGES, false)
        set(value) { prefs.edit().putBoolean(KEY_PROTECT_MESSAGES, value).apply() }

    /** The webhook URL for which "the endpoint isn't confirming records" was last notified, so it's
     *  said once per endpoint rather than on every upload. */
    var unconfirmedNotifiedUrl: String
        get() = prefs.getString(KEY_UNCONFIRMED_NOTIFIED_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_UNCONFIRMED_NOTIFIED_URL, value).apply() }

    /** The Status screen's DB-status text as of the last time it was computed, so a cold app start
     *  can paint it immediately instead of showing nothing while Room re-queries. Blank = none yet. */
    var statusCacheLastUpload: String
        get() = prefs.getString(KEY_STATUS_CACHE_LAST_UPLOAD, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_STATUS_CACHE_LAST_UPLOAD, value).apply()
        }

    /** When we last heard a heartbeat from the peer (companion). 0 = not baselined / not paired. */
    var lastPeerBeatAtMs: Long
        get() = prefs.getLong(KEY_LAST_PEER_BEAT_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_PEER_BEAT_MS, value).apply()
        }

    /** Main is paired with a companion it can reach (endpoint + key + FCM key all present). */
    fun peerPaired(): Boolean =
        sndiFcmToken.isNotBlank() && relayKey.isNotBlank() && serviceAccountJson.isNotBlank()

    /** Whether the liveness heartbeat runs on this device (send + monitor). Default on. */
    var heartbeatEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEARTBEAT_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HEARTBEAT_ENABLED, value).apply()
        }

    // ---- Companion device status ----

    /** Whether Main asks the companion for its status and honours its alerts. Default on. */
    var companionStatusEnabled: Boolean
        get() = prefs.getBoolean(KEY_CS_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_CS_ENABLED, value).apply() }

    /** Whether the companion should raise battery/wifi alerts. Pushed down to it. */
    var companionAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CS_ALERTS, true)
        set(value) { prefs.edit().putBoolean(KEY_CS_ALERTS, value).apply() }

    var companionBatteryPct: Int
        get() = prefs.getInt(KEY_CS_BATTERY_PCT, DeviceStatusPolicy.DEFAULT_BATTERY_PCT)
        set(value) { prefs.edit().putInt(KEY_CS_BATTERY_PCT, DeviceStatusPolicy.clampBatteryPct(value)).apply() }

    var companionWifiLossMinutes: Int
        get() = prefs.getInt(KEY_CS_WIFI_MIN, DeviceStatusPolicy.DEFAULT_WIFI_LOSS_MINUTES)
        set(value) { prefs.edit().putInt(KEY_CS_WIFI_MIN, DeviceStatusPolicy.clampWifiLossMinutes(value)).apply() }

    var companionPollSeconds: Int
        get() = prefs.getInt(KEY_CS_POLL_SEC, DeviceStatusPolicy.DEFAULT_POLL_SECONDS)
        set(value) { prefs.edit().putInt(KEY_CS_POLL_SEC, DeviceStatusPolicy.clampPollSeconds(value)).apply() }

    /** The last snapshot received, as a wire-encoded [com.noti.shared.WireMessage.StatusReport], so
     *  the Status card can paint a cold start instead of showing nothing until the first poll. */
    var lastCompanionStatus: String
        get() = prefs.getString(KEY_CS_LAST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CS_LAST, value).apply() }

    /** Show the value under each companion indicator. Off leaves the icons alone, which is the
     *  most compact the strip gets. */
    var companionStatusLabels: Boolean
        get() = prefs.getBoolean(KEY_CS_LABELS, true)
        set(value) { prefs.edit().putBoolean(KEY_CS_LABELS, value).apply() }

    /** When that snapshot arrived, on *this* device's clock - freshness must never be judged by
     *  comparing against the companion's clock. See [DeviceStatusPolicy.readoutSuspect]. */
    var lastCompanionStatusAtMs: Long
        get() = prefs.getLong(KEY_CS_LAST_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_CS_LAST_AT, value).apply() }


    // ---- Reminder module ----

    /** The central on/off for the whole Reminder module: rulebook, scan, tab and reminders. Off by
     *  default - it is a distinct feature that shouldn't appear for anyone who didn't ask for it. */
    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_RM_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_RM_ENABLED, value).apply() }

    /** How often the rulebook runs, in hours. 0 = never (manual "Calculate now" only). */
    var reminderScanHours: Int
        get() = prefs.getInt(KEY_RM_SCAN_HOURS, ReminderPolicy.DEFAULT_SCAN_HOURS)
        set(value) { prefs.edit().putInt(KEY_RM_SCAN_HOURS, value.coerceIn(0, 168)).apply() }

    /** Time of day bill reminders fire. */
    var reminderHour: Int
        get() = prefs.getInt(KEY_RM_HOUR, ReminderPolicy.DEFAULT_REMIND_HOUR)
        set(value) { prefs.edit().putInt(KEY_RM_HOUR, value.coerceIn(0, 23)).apply() }

    var reminderMinute: Int
        get() = prefs.getInt(KEY_RM_MINUTE, ReminderPolicy.DEFAULT_REMIND_MINUTE)
        set(value) { prefs.edit().putInt(KEY_RM_MINUTE, value.coerceIn(0, 59)).apply() }

    /** How many days before the due date to remind. */
    var reminderDaysBefore: Int
        get() = prefs.getInt(KEY_RM_DAYS_BEFORE, ReminderPolicy.DEFAULT_REMIND_DAYS_BEFORE)
        set(value) { prefs.edit().putInt(KEY_RM_DAYS_BEFORE, value.coerceIn(0, 30)).apply() }

    /** Minutes between reminders that would otherwise land together. */
    var reminderSpacingMinutes: Int
        get() = prefs.getInt(KEY_RM_SPACING, ReminderPolicy.DEFAULT_SPACING_MINUTES)
        set(value) { prefs.edit().putInt(KEY_RM_SPACING, value.coerceIn(0, 60)).apply() }

    /** How far ahead of a parking expiry to be reminded. */
    var parkingLeadMinutes: Int
        get() = prefs.getInt(KEY_RM_PARK_LEAD, ReminderPolicy.DEFAULT_PARKING_LEAD_MINUTES)
        set(value) { prefs.edit().putInt(KEY_RM_PARK_LEAD, value.coerceIn(0, 180)).apply() }

    /** Which backend rings a parking expiry - see [com.noti.logger.reminder.ParkingAlarmMode].
     *  Defaults to IN_APP because device testing showed the clock-app route is blocked from the
     *  background, which is where every real parking renewal arrives. */
    var parkingAlarmMode: String
        get() = prefs.getString(KEY_RM_PARK_ALARM, "IN_APP") ?: "IN_APP"
        set(value) { prefs.edit().putString(KEY_RM_PARK_ALARM, value).apply() }

    /** End of the window the last scan covered, so the next one resumes from exactly there and
     *  neither re-reads the whole table nor skips a message that arrived mid-scan. */
    var lastReminderScanAtMs: Long
        get() = prefs.getLong(KEY_RM_LAST_SCAN, 0L)
        set(value) { prefs.edit().putLong(KEY_RM_LAST_SCAN, value).apply() }

    /** Highest relayed-message row id the last scan covered; -1 = none yet (fall back to the time
     *  window). Reset by a restore, which reassigns row ids. */
    var lastReminderScanSmsId: Long
        get() = prefs.getLong(KEY_RM_LAST_SCAN_SMS_ID, -1L)
        set(value) { prefs.edit().putLong(KEY_RM_LAST_SCAN_SMS_ID, value).apply() }

    /** Highest captured-notification row id the last scan covered; -1 = none yet (fall back to the
     *  time window). */
    var lastReminderScanNotifId: Long
        get() = prefs.getLong(KEY_RM_LAST_SCAN_NOTIF_ID, -1L)
        set(value) { prefs.edit().putLong(KEY_RM_LAST_SCAN_NOTIF_ID, value).apply() }

    /** Reminders tab: one line per entry instead of a card with labelled buttons. A view
     *  preference for this phone, so it is not carried in backups. */
    var remindersCompact: Boolean
        get() = prefs.getBoolean(KEY_RM_COMPACT, false)
        set(value) { prefs.edit().putBoolean(KEY_RM_COMPACT, value).apply() }

    /** Reminders tab: the "due in the next 30 days" total at the top. */
    var remindersShowTotal: Boolean
        get() = prefs.getBoolean(KEY_RM_SHOW_TOTAL, true)
        set(value) { prefs.edit().putBoolean(KEY_RM_SHOW_TOTAL, value).apply() }

    /** The currency the user chose for Reminders totals (ISO code), or "" to follow the phone's
     *  region - see [com.noti.logger.reminder.FxRates.home], which resolves the effective one. */
    var defaultCurrency: String
        get() = prefs.getString(KEY_DEFAULT_CURRENCY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_DEFAULT_CURRENCY, value).apply() }

    /** Cached exchange rates (the API's JSON, based on the default currency) for that total, when they were fetched,
     *  and when a fetch was last tried - see [com.noti.logger.reminder.FxRates]. */
    var fxRatesJson: String
        get() = prefs.getString(KEY_FX_JSON, "") ?: ""
        set(value) { prefs.edit().putString(KEY_FX_JSON, value).apply() }
    var fxFetchedAtMs: Long
        get() = prefs.getLong(KEY_FX_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_FX_AT, value).apply() }
    var fxAttemptAtMs: Long
        get() = prefs.getLong(KEY_FX_TRIED, 0L)
        set(value) { prefs.edit().putLong(KEY_FX_TRIED, value).apply() }

    /** Days of settled bills to keep in history. */
    var reminderHistoryDays: Int
        get() = prefs.getInt(KEY_RM_HISTORY_DAYS, 730)
        set(value) { prefs.edit().putInt(KEY_RM_HISTORY_DAYS, value.coerceAtLeast(0)).apply() }

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

    /** Senders whose new messages are stored (and shown as unread) but don't raise a notification.
     *  Memoized - the whole set is consulted per inbound notification. */
    @Volatile
    private var cachedMutedSenders: Set<String>? = null
    var mutedSenders: Set<String>
        get() {
            cachedMutedSenders?.let { return it }
            synchronized(this) {
                cachedMutedSenders?.let { return it }
                return (prefs.getStringSet(KEY_MUTED_SENDERS, emptySet()) ?: emptySet())
                    .also { cachedMutedSenders = it }
            }
        }
        set(value) {
            prefs.edit().putStringSet(KEY_MUTED_SENDERS, value).apply()
            cachedMutedSenders = value
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
        private const val KEY_CHAT_RETENTION_DAYS = "chat_retention_days"
        private const val KEY_DEDUPE_WINDOW_SECONDS = "dedupe_window_seconds"
        private const val KEY_ROLE = "role"
        private const val KEY_PUSH_INBOUND_ENABLED = "push_inbound_enabled"
        private const val KEY_OTP_COPY = "otp_copy_enabled"
        private const val KEY_SUPPRESS_SYS_ACTIONS = "suppress_system_notif_actions"
        private const val KEY_RELAY_KEY = "relay_key"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_ANNOUNCED_TOKEN = "announced_token"
        private const val KEY_SA_JSON = "sa_json"
        private const val KEY_FIREBASE_CONFIG_JSON = "firebase_config_json"
        private const val KEY_SNDI_TOKEN = "sndi_token"
        private const val KEY_LAST_UPLOAD_AT_MS = "last_upload_at_ms"
        private const val KEY_LAST_UPLOAD_RESULT = "last_upload_result"
        private const val KEY_UNCONFIRMED_NOTIFIED_URL = "unconfirmed_notified_url"
        private const val KEY_PROTECT_KEYS = "protect_key_screens"
        private const val KEY_DB_KEY = "db_key"
        private const val KEY_PROTECT_MESSAGES = "protect_messages"
        private const val KEY_STATUS_CACHE_LAST_UPLOAD = "status_cache_last_upload"
        private const val KEY_LAST_PEER_BEAT_MS = "last_peer_beat_ms"
        private const val KEY_HEARTBEAT_ENABLED = "heartbeat_enabled"
        private const val KEY_CS_ENABLED = "companion_status_enabled"
        private const val KEY_CS_ALERTS = "companion_alerts_enabled"
        private const val KEY_CS_BATTERY_PCT = "companion_battery_pct"
        private const val KEY_CS_WIFI_MIN = "companion_wifi_loss_minutes"
        private const val KEY_CS_POLL_SEC = "companion_poll_seconds"
        private const val KEY_CS_LAST = "companion_status_last"
        private const val KEY_CS_LAST_AT = "companion_status_last_at"
        private const val KEY_CS_LABELS = "companion_status_labels"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_MUTED_SENDERS = "muted_senders"
        private const val KEY_RM_ENABLED = "reminder_enabled"
        private const val KEY_RM_SCAN_HOURS = "reminder_scan_hours"
        private const val KEY_RM_HOUR = "reminder_hour"
        private const val KEY_RM_MINUTE = "reminder_minute"
        private const val KEY_RM_DAYS_BEFORE = "reminder_days_before"
        private const val KEY_RM_SPACING = "reminder_spacing_minutes"
        private const val KEY_RM_PARK_LEAD = "reminder_parking_lead_minutes"
        private const val KEY_RM_PARK_ALARM = "reminder_parking_alarm_mode"
        private const val KEY_RM_LAST_SCAN = "reminder_last_scan_at"
        private const val KEY_RM_LAST_SCAN_SMS_ID = "reminder_last_scan_sms_id"
        private const val KEY_RM_LAST_SCAN_NOTIF_ID = "reminder_last_scan_notif_id"
        private const val KEY_RM_HISTORY_DAYS = "reminder_history_days"
        private const val KEY_RM_COMPACT = "reminders_compact"
        private const val KEY_RM_SHOW_TOTAL = "reminders_show_total"
        private const val KEY_FX_JSON = "fx_rates_json"
        private const val KEY_FX_AT = "fx_rates_fetched_at"
        private const val KEY_FX_TRIED = "fx_rates_attempt_at"
        private const val KEY_DEFAULT_CURRENCY = "default_currency"

        @Volatile
        private var INSTANCE: Settings? = null

        fun get(context: Context): Settings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Settings(openPrefs(context.applicationContext)).also { INSTANCE = it }
            }
        }

        private fun openPrefs(appContext: Context): SharedPreferences {
            // Building the master key can fail on its own (locked keystore before first unlock,
            // a flaky keystore IPC, a broken provider) - that's a transient/device problem, not a
            // sign the stored data is corrupt, so let it propagate untouched rather than deleting
            // a file that was never the issue.
            val masterKey = buildMasterKey(appContext)
            return try {
                buildEncryptedPrefs(appContext, masterKey)
            } catch (e: Exception) {
                // The master key itself works, but the keyset file under it won't decrypt -
                // either genuinely corrupt, or (e.g. after an app-data restore, where keystore
                // keys never travel with the backup) it was encrypted under a master key that no
                // longer exists on this device. Either way the data is unrecoverable, so wipe and
                // start fresh rather than crash-looping forever. This does mean silently losing
                // pairing/relay key/webhook config - there's no UI to warn from here, so at least
                // log loudly for crash/bug reports.
                Log.e("Settings", "Encrypted settings keyset unreadable under a valid master key, resetting", e)
                deletePrefsFile(appContext)
                try {
                    buildEncryptedPrefs(appContext, masterKey)
                } catch (e2: Exception) {
                    throw IllegalStateException("Encrypted settings are unusable even after a reset", e2)
                }
            }
        }

        private fun buildMasterKey(appContext: Context): MasterKey =
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

        private fun buildEncryptedPrefs(appContext: Context, masterKey: MasterKey): SharedPreferences =
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

        private fun deletePrefsFile(appContext: Context) {
            val prefsDir = File(appContext.filesDir.parent ?: return, "shared_prefs")
            File(prefsDir, "$PREFS_NAME.xml").delete()
        }
    }
}
