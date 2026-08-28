package com.noti.logger.backup

import android.content.Context
import com.noti.logger.config.AppRules
import com.noti.logger.config.Settings
import com.noti.logger.config.ThemeMode
import com.noti.logger.config.TriggerMode
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The full contents of a backup: the meaningful settings (including the pairing secrets, so a
 * restore is turnkey) plus the entire message history. Device-specific/status values (this device's
 * own FCM token, last-upload status) are intentionally omitted - they're re-derived on the device.
 * The whole thing is JSON, then passphrase-encrypted by [com.noti.shared.BackupCrypto] before it
 * ever leaves the app.
 */
object Backup {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Payload(
        val version: Int = 1,
        val settings: SettingsData,
        val messages: List<MessageData>,
    )

    @Serializable
    data class SettingsData(
        val webhookUrl: String, val bearerToken: String,
        val authHeaderName: String, val authHeaderPrefix: String, val gzipEnabled: Boolean,
        val triggerMode: String, val intervalMinutes: Int, val thresholdCount: Int,
        val requireUnmetered: Boolean, val requireCharging: Boolean,
        val includedPackages: List<String>, val excludedKeywords: List<String>, val appRulesEncoded: String,
        val captureBody: Boolean, val retentionDays: Int, val dedupeWindowSeconds: Int,
        val pushInboundEnabled: Boolean, val relayKey: String,
        val serviceAccountJson: String, val sndiFcmToken: String,
        val themeMode: String, val dynamicColor: Boolean, val amoled: Boolean,
        val mutedSenders: List<String>,
    )

    @Serializable
    data class MessageData(
        val sender: String, val sim: String, val body: String, val receivedAt: Long,
        val outgoing: Int, val read: Int, val dedupe: String,
    )

    /** Serializes settings + messages to a JSON string (call off the main thread). */
    fun export(context: Context): String {
        val s = Settings.get(context)
        val dao = NotiDatabase.get(context).relayedMessageDao()
        val payload = Payload(
            settings = SettingsData(
                webhookUrl = s.webhookUrl, bearerToken = s.bearerToken,
                authHeaderName = s.authHeaderName, authHeaderPrefix = s.authHeaderPrefix, gzipEnabled = s.gzipEnabled,
                triggerMode = s.triggerMode.name, intervalMinutes = s.intervalMinutes, thresholdCount = s.thresholdCount,
                requireUnmetered = s.requireUnmetered, requireCharging = s.requireCharging,
                includedPackages = s.includedPackages.toList(), excludedKeywords = s.excludedKeywords,
                appRulesEncoded = AppRules.encode(s.appRules),
                captureBody = s.captureBody, retentionDays = s.retentionDays, dedupeWindowSeconds = s.dedupeWindowSeconds,
                pushInboundEnabled = s.pushInboundEnabled, relayKey = s.relayKey,
                serviceAccountJson = s.serviceAccountJson, sndiFcmToken = s.sndiFcmToken,
                themeMode = s.themeMode.name, dynamicColor = s.dynamicColor, amoled = s.amoled,
                mutedSenders = s.mutedSenders.toList(),
            ),
            messages = dao.allMessages().map {
                MessageData(it.sender, it.sim, it.body, it.receivedAt, it.outgoing, it.read, it.dedupe)
            },
        )
        return json.encodeToString(payload)
    }

    /** Replaces settings + message history from a backup JSON string (call off the main thread). */
    fun import(context: Context, jsonText: String) {
        val payload = json.decodeFromString<Payload>(jsonText)
        val s = Settings.get(context)
        with(payload.settings) {
            s.webhookUrl = webhookUrl; s.bearerToken = bearerToken
            s.authHeaderName = authHeaderName; s.authHeaderPrefix = authHeaderPrefix; s.gzipEnabled = gzipEnabled
            s.triggerMode = runCatching { TriggerMode.valueOf(triggerMode) }.getOrDefault(TriggerMode.PERIODIC)
            s.intervalMinutes = intervalMinutes; s.thresholdCount = thresholdCount
            s.requireUnmetered = requireUnmetered; s.requireCharging = requireCharging
            s.includedPackages = includedPackages.toSet(); s.excludedKeywords = excludedKeywords
            s.appRules = AppRules.decode(appRulesEncoded)
            s.captureBody = captureBody; s.retentionDays = retentionDays; s.dedupeWindowSeconds = dedupeWindowSeconds
            s.pushInboundEnabled = pushInboundEnabled; s.relayKey = relayKey
            s.serviceAccountJson = serviceAccountJson; s.sndiFcmToken = sndiFcmToken
            s.themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM)
            s.dynamicColor = dynamicColor; s.amoled = amoled
            s.mutedSenders = mutedSenders.toSet()
        }
        val dao = NotiDatabase.get(context).relayedMessageDao()
        dao.replaceAll(
            payload.messages.map {
                RelayedMessageEntity(
                    sender = it.sender, sim = it.sim, body = it.body, receivedAt = it.receivedAt,
                    outgoing = it.outgoing, read = it.read, dedupe = it.dedupe,
                )
            }
        )
    }
}
