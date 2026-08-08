package com.noti.sender.config

import android.content.Context
import android.content.SharedPreferences
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

    /** Service-account JSON used to authenticate FCM sends (imported via the file picker). */
    var serviceAccountJson: String
        get() = prefs.getString(KEY_SA_JSON, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SA_JSON, value).apply() }

    /** Pre-shared AES-256-GCM key (base64), the same value noti holds. */
    var relayKey: String
        get() = prefs.getString(KEY_RELAY_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_RELAY_KEY, value.trim()).apply() }

    /** noti's FCM registration token (the push target), obtained at pairing. */
    var notiFcmToken: String
        get() = prefs.getString(KEY_NOTI_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_NOTI_TOKEN, value.trim()).apply() }

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
        private const val KEY_RELAY_KEY = "relay_key"
        private const val KEY_NOTI_TOKEN = "noti_token"
        private const val KEY_MY_TOKEN = "my_token"
        private const val KEY_ACCEPT_COMMANDS = "accept_commands"
        private const val KEY_FCM_ENABLED = "fcm_enabled"
        private const val KEY_N8N_ENABLED = "n8n_enabled"
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

        @Volatile private var INSTANCE: SenderSettings? = null

        fun get(context: Context): SenderSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SenderSettings(openPrefs(context.applicationContext)).also { INSTANCE = it }
            }

        private fun openPrefs(appContext: Context): SharedPreferences = try {
            buildEncryptedPrefs(appContext)
        } catch (e: Exception) {
            // Corrupt or missing keyset — wipe and recreate.
            File(appContext.filesDir.parent ?: "", "shared_prefs/$PREFS_NAME.xml").delete()
            buildEncryptedPrefs(appContext)
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
    }
}
