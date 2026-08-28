package com.noti.sender.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.UUID

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Encrypted-at-rest settings for the sender: the service-account key and AES key are secrets, so
 * everything lives in EncryptedSharedPreferences (same approach as noti's Settings).
 */
class SenderSettings private constructor(private val prefs: SharedPreferences) {

    /** Service-account JSON used to authenticate FCM sends (imported via the file picker).
     *  Memoized like [deviceId] - it's a multi-KB blob, and EncryptedSharedPreferences decrypts
     *  per read; this gets read on every SMS relay / heartbeat / token announce. */
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
     *  at build time. */
    var firebaseConfigJson: String
        get() = prefs.getString(KEY_FIREBASE_CONFIG_JSON, "") ?: ""
        set(value) { prefs.edit().putString(KEY_FIREBASE_CONFIG_JSON, value).apply() }

    /** Pre-shared AES-256-GCM key (base64), the same value noti holds. Memoized - read on every
     *  relayed SMS and heartbeat. */
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

    /** noti's FCM registration token (the push target), obtained at pairing. Memoized. */
    @Volatile
    private var cachedNotiFcmToken: String? = null
    var notiFcmToken: String
        get() {
            cachedNotiFcmToken?.let { return it }
            synchronized(this) {
                cachedNotiFcmToken?.let { return it }
                return (prefs.getString(KEY_NOTI_TOKEN, "") ?: "").also { cachedNotiFcmToken = it }
            }
        }
        set(value) {
            val trimmed = value.trim()
            prefs.edit().putString(KEY_NOTI_TOKEN, trimmed).apply()
            cachedNotiFcmToken = trimmed
        }

    /** Last token we know Main received (set after a successful announce), so the process-start
     *  announce only fires when the token actually changed. */
    var announcedToken: String
        get() = prefs.getString(KEY_ANNOUNCED_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ANNOUNCED_TOKEN, value).apply() }

    /** This device's own FCM token, so noti can push send-SMS commands here (Phase B). */
    var myFcmToken: String
        get() = prefs.getString(KEY_MY_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MY_TOKEN, value).apply() }

    /** Opt-in: honor send-SMS commands pushed from noti (requires SEND_SMS). Default off. */
    var acceptCommands: Boolean
        get() = prefs.getBoolean(KEY_ACCEPT_COMMANDS, false)
        set(value) { prefs.edit().putBoolean(KEY_ACCEPT_COMMANDS, value).apply() }

    var fcmEnabled: Boolean
        get() = prefs.getBoolean(KEY_FCM_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_FCM_ENABLED, value).apply() }

    var n8nEnabled: Boolean
        get() = prefs.getBoolean(KEY_N8N_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_N8N_ENABLED, value).apply() }

    /** Opt-in for accepting webhook config pushed from Main. Default OFF: a pushed config silently
     *  overwrites this device's webhook url/auth with no confirmation, so this stays local-only
     *  until the user deliberately turns it on. */
    var acceptRemoteConfig: Boolean
        get() = prefs.getBoolean(KEY_ACCEPT_REMOTE_CONFIG, false)
        set(value) { prefs.edit().putBoolean(KEY_ACCEPT_REMOTE_CONFIG, value).apply() }

    var n8nUrl: String
        get() = prefs.getString(KEY_N8N_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_N8N_URL, value.trim()).apply() }

    var n8nAuthHeaderName: String
        get() = prefs.getString(KEY_N8N_HEADER_NAME, "Authorization") ?: "Authorization"
        set(value) { prefs.edit().putString(KEY_N8N_HEADER_NAME, value).apply() }

    var n8nAuthHeaderPrefix: String
        get() = prefs.getString(KEY_N8N_HEADER_PREFIX, "Bearer ") ?: "Bearer "
        set(value) { prefs.edit().putString(KEY_N8N_HEADER_PREFIX, value).apply() }

    var n8nToken: String
        get() = prefs.getString(KEY_N8N_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_N8N_TOKEN, value).apply() }

    fun n8nAuthValue(): String = n8nAuthHeaderPrefix + n8nToken

    /** User-given names for each SIM slot, so the payload can show the carrier without needing
     *  READ_PHONE_STATE. Blank falls back to "SIM 1" / "SIM 2". */
    var sim1Name: String
        get() = prefs.getString(KEY_SIM1_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SIM1_NAME, value.trim()).apply() }

    var sim2Name: String
        get() = prefs.getString(KEY_SIM2_NAME, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SIM2_NAME, value.trim()).apply() }

    /** The label for a 0-based SIM slot: the user's name, or a "SIM N" default. */
    fun simName(slot: Int): String = when (slot) {
        1 -> sim2Name.ifBlank { "SIM 2" }
        else -> sim1Name.ifBlank { "SIM 1" }
    }

    // ---- Appearance ----

    var themeMode: ThemeMode
        get() = try {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
        set(value) { prefs.edit().putString(KEY_THEME_MODE, value.name).apply() }

    /** false = Default (blue brand theme); true = Material You dynamic color (Android 12+). */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) { prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply() }

    /** Pure-black (AMOLED) surfaces when dark mode is active. No effect in light mode. */
    var amoled: Boolean
        get() = prefs.getBoolean(KEY_AMOLED, false)
        set(value) { prefs.edit().putBoolean(KEY_AMOLED, value).apply() }

    // ---- Keep-alive / missed-SMS sync ----

    /** Run the persistent foreground service to resist Doze / OEM app-standby. Default on. */
    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEPALIVE, true)
        set(value) { prefs.edit().putBoolean(KEY_KEEPALIVE, value).apply() }

    /** Provider `_id` high-water mark for the ippu (FCM) relay leg. -1 = not yet baselined. */
    var lastRelayedSmsId: Long
        get() = prefs.getLong(KEY_LAST_RELAYED_ID, -1L)
        set(value) { prefs.edit().putLong(KEY_LAST_RELAYED_ID, value).apply() }

    /** Provider `_id` high-water mark for the webhook (n8n) leg. -1 = not yet baselined. Independent
     *  of the FCM mark so one leg failing/retrying can't re-send the other. */
    var lastWebhookSmsId: Long
        get() = prefs.getLong(KEY_LAST_WEBHOOK_ID, -1L)
        set(value) { prefs.edit().putLong(KEY_LAST_WEBHOOK_ID, value).apply() }

    /** Replay-protection high-water mark: the highest [com.noti.shared.WireMessage.Command.msgId]
     *  accepted so far. -1 = none yet. Persisted so a captured/replayed command can't slip through
     *  after an app or device restart. */
    var lastCommandMsgId: Long
        get() = prefs.getLong(KEY_LAST_COMMAND_MSG_ID, -1L)
        set(value) { prefs.edit().putLong(KEY_LAST_COMMAND_MSG_ID, value).apply() }

    /** Replay/rollback guard: the highest [com.noti.shared.WireMessage.WebhookConfig.configVersion]
     *  applied so far. -1 = none yet (accepts the first config pushed, whatever its version). */
    var lastWebhookConfigVersion: Long
        get() = prefs.getLong(KEY_LAST_WEBHOOK_CONFIG_VERSION, -1L)
        set(value) { prefs.edit().putLong(KEY_LAST_WEBHOOK_CONFIG_VERSION, value).apply() }

    // ---- Full repush (one-shot: push the entire inbox so ippu can rebuild after a gap) ----

    /** Resume point (provider `_id`) for an in-progress repush (0 = start from the whole inbox). */
    var repushCursorId: Long
        get() = prefs.getLong(KEY_REPUSH_CURSOR, 0L)
        set(value) { prefs.edit().putLong(KEY_REPUSH_CURSOR, value).apply() }

    /** Inbox size captured when a repush starts, for the progress notification. */
    var repushTotal: Int
        get() = prefs.getInt(KEY_REPUSH_TOTAL, 0)
        set(value) { prefs.edit().putInt(KEY_REPUSH_TOTAL, value).apply() }

    // ---- Heartbeat ----

    /** When we last heard a heartbeat from the peer (Main). 0 = not baselined / not paired. */
    var lastPeerBeatAtMs: Long
        get() = prefs.getLong(KEY_LAST_PEER_BEAT_MS, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_PEER_BEAT_MS, value).apply() }

    /** Companion is paired with a Main it can reach (endpoint + key + FCM key all present). */
    fun peerPaired(): Boolean =
        notiFcmToken.isNotBlank() && relayKey.isNotBlank() && serviceAccountJson.isNotBlank()

    /** Whether the liveness heartbeat runs on this device (send + monitor). Default on. */
    var heartbeatEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEARTBEAT_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_HEARTBEAT_ENABLED, value).apply() }

    /** Generated once, stable for the install; identifies this device in the n8n payload. */
    @Volatile private var cachedDeviceId: String? = null
    val deviceId: String
        get() {
            cachedDeviceId?.let { return it }
            synchronized(this) {
                cachedDeviceId?.let { return it }
                val id = prefs.getString(KEY_DEVICE_ID, null)
                    ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
                cachedDeviceId = id
                return id
            }
        }

    companion object {
        private const val PREFS_NAME = "noti_sender_settings"

        private const val KEY_SA_JSON = "sa_json"
        private const val KEY_FIREBASE_CONFIG_JSON = "firebase_config_json"
        private const val KEY_RELAY_KEY = "relay_key"
        private const val KEY_NOTI_TOKEN = "noti_token"
        private const val KEY_MY_TOKEN = "my_token"
        private const val KEY_ANNOUNCED_TOKEN = "announced_token"
        private const val KEY_ACCEPT_COMMANDS = "accept_commands"
        private const val KEY_FCM_ENABLED = "fcm_enabled"
        private const val KEY_N8N_ENABLED = "n8n_enabled"
        private const val KEY_ACCEPT_REMOTE_CONFIG = "accept_remote_config"
        private const val KEY_N8N_URL = "n8n_url"
        private const val KEY_N8N_HEADER_NAME = "n8n_header_name"
        private const val KEY_N8N_HEADER_PREFIX = "n8n_header_prefix"
        private const val KEY_N8N_TOKEN = "n8n_token"
        private const val KEY_SIM1_NAME = "sim1_name"
        private const val KEY_SIM2_NAME = "sim2_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_KEEPALIVE = "keepalive"
        private const val KEY_LAST_RELAYED_ID = "last_relayed_sms_id"
        private const val KEY_LAST_WEBHOOK_ID = "last_webhook_sms_id"
        private const val KEY_LAST_COMMAND_MSG_ID = "last_command_msg_id"
        private const val KEY_LAST_WEBHOOK_CONFIG_VERSION = "last_webhook_config_version"
        private const val KEY_REPUSH_CURSOR = "repush_cursor_id"
        private const val KEY_REPUSH_TOTAL = "repush_total"
        private const val KEY_LAST_PEER_BEAT_MS = "last_peer_beat_ms"
        private const val KEY_HEARTBEAT_ENABLED = "heartbeat_enabled"

        @Volatile private var INSTANCE: SenderSettings? = null

        fun get(context: Context): SenderSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SenderSettings(openPrefs(context.applicationContext)).also { INSTANCE = it }
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
                // the relay key/service-account credentials - there's no UI to warn from here, so
                // at least log loudly for crash/bug reports.
                Log.e("SenderSettings", "Encrypted settings keyset unreadable under a valid master key, resetting", e)
                File(appContext.filesDir.parent ?: "", "shared_prefs/$PREFS_NAME.xml").delete()
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
    }
}
