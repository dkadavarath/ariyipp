package com.noti.logger.backup

import android.content.Context
import com.noti.logger.config.AppRules
import com.noti.logger.config.Settings
import com.noti.logger.config.ThemeMode
import com.noti.logger.config.TriggerMode
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.ParkingSessionEntity
import com.noti.logger.data.RelayedMessageEntity
import com.noti.logger.data.ReminderRuleEntity
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

    /**
     * 3: due dates and rule anchor dates are plain dates (UTC midnight, see DueDates); before, they
     * were the backing-up phone's local midnight and are converted on restore.
     */
    const val VERSION = 3

    @Serializable
    data class Payload(
        /** Format version. Written explicitly by [export]; a file without one is the oldest kind. */
        val version: Int = 1,
        val settings: SettingsData,
        val messages: List<MessageData>,
        /**
         * The Reminder module. Defaulted to empty so a backup written before v2 still restores -
         * decoding an older file simply finds no reminders rather than failing, which matters
         * because a backup is worth least on the day you most need it to still open.
         */
        val reminderRules: List<RuleData> = emptyList(),
        val bills: List<BillData> = emptyList(),
        val parking: List<ParkingData> = emptyList(),
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
        // Defaulted so backups made before chat retention had its own setting still restore.
        val chatRetentionDays: Int = 0,
        // ---- Added for the full-settings backup. All nullable: a file written before this carries
        // none of them, and restoring it must leave the current values alone rather than reset them.
        /** The imported google-services.json - same for any phone on this Firebase project, and
         *  without it a restored phone has the key but cannot start push. */
        val firebaseConfigJson: String? = null,
        val otpCopyEnabled: Boolean? = null,
        val suppressSystemNotifActions: Boolean? = null,
        val heartbeatEnabled: Boolean? = null,
        val companionStatusEnabled: Boolean? = null,
        val companionAlertsEnabled: Boolean? = null,
        val companionBatteryPct: Int? = null,
        val companionWifiLossMinutes: Int? = null,
        val companionPollSeconds: Int? = null,
        val companionStatusLabels: Boolean? = null,
        val reminderEnabled: Boolean? = null,
        val reminderScanHours: Int? = null,
        val reminderHour: Int? = null,
        val reminderMinute: Int? = null,
        val reminderDaysBefore: Int? = null,
        val reminderSpacingMinutes: Int? = null,
        val parkingLeadMinutes: Int? = null,
        val parkingAlarmMode: String? = null,
        val reminderHistoryDays: Int? = null,
        val remindersCompact: Boolean? = null,
        val remindersShowTotal: Boolean? = null,
        /** "" = follow the phone's region; carried as chosen so a restore keeps "automatic". */
        val defaultCurrency: String? = null,
    )

    @Serializable
    data class MessageData(
        val sender: String, val sim: String, val body: String, val receivedAt: Long,
        val outgoing: Int, val read: Int, val dedupe: String,
    )

    /**
     * A rulebook entry. Worth backing up for a reason the other tables are not: these are hand-
     * written regexes, tuned against real messages over time, and nothing else in the app can
     * reconstruct them.
     */
    @Serializable
    data class RuleData(
        val name: String, val type: String, val source: String,
        val sourcePackage: String, val sourceSender: String, val enabled: Int,
        val matchPattern: String, val amountPattern: String, val currencyPattern: String,
        val datePattern: String, val zonePattern: String, val expiryPattern: String,
        val dateFormat: String, val dueMode: String,
        val constantDay: Int, val anchorDay: Int, val offsetDays: Int,
        val recurrence: String, val recurrenceEvery: Int, val anchorDate: Long,
        val defaultCurrency: String, val skipNext: Int, val createdAt: Long,
        val notes: String = "", val category: String = "", val remindDaysBefore: Int = -1,
        val defaultAmount: Double? = null,
        val payEnabled: Int = 0, val paySource: String = "SMS", val paySender: String = "",
        val payPackage: String = "", val payMatchPattern: String = "", val payAmountPattern: String = "",
        val payMatchAmount: Int = 0,
    )

    /** One bill, settled or outstanding - the history the tab shows. */
    @Serializable
    data class BillData(
        val ruleName: String, val name: String, val dueAt: Long,
        val amount: Double?, val currency: String, val amountRaw: String, val dueRaw: String,
        val status: Int, val sourceKey: String, val createdAt: Long, val settledAt: Long,
        val notes: String = "", val category: String = "", val remindDaysBefore: Int = -1,
        val issuedAt: Long = 0, val settleKey: String = "", val autoSettled: Int = 0,
        val editedFields: Int = 0,
    )

    @Serializable
    data class ParkingData(
        val name: String, val zone: String, val expiresAt: Long,
        val active: Int, val renewals: Int, val startedAt: Long, val endedAt: Long,
    )

    /** Serializes settings + messages + reminders to a JSON string (call off the main thread). */
    fun export(context: Context): String {
        val s = Settings.get(context)
        val db = NotiDatabase.get(context)
        val dao = db.relayedMessageDao()
        val ruleDao = db.reminderRuleDao()
        val billDao = db.billOccurrenceDao()
        val parkingDao = db.parkingSessionDao()
        val rules = try { ruleDao.all() } catch (e: Exception) { emptyList() }
        val payload = Payload(
            version = VERSION,
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
                chatRetentionDays = s.chatRetentionDays,
                firebaseConfigJson = s.firebaseConfigJson,
                otpCopyEnabled = s.otpCopyEnabled, suppressSystemNotifActions = s.suppressSystemNotifActions,
                heartbeatEnabled = s.heartbeatEnabled,
                companionStatusEnabled = s.companionStatusEnabled, companionAlertsEnabled = s.companionAlertsEnabled,
                companionBatteryPct = s.companionBatteryPct, companionWifiLossMinutes = s.companionWifiLossMinutes,
                companionPollSeconds = s.companionPollSeconds, companionStatusLabels = s.companionStatusLabels,
                reminderEnabled = s.reminderEnabled, reminderScanHours = s.reminderScanHours,
                reminderHour = s.reminderHour, reminderMinute = s.reminderMinute,
                reminderDaysBefore = s.reminderDaysBefore, reminderSpacingMinutes = s.reminderSpacingMinutes,
                parkingLeadMinutes = s.parkingLeadMinutes, parkingAlarmMode = s.parkingAlarmMode,
                reminderHistoryDays = s.reminderHistoryDays,
                remindersCompact = s.remindersCompact, remindersShowTotal = s.remindersShowTotal,
                defaultCurrency = s.defaultCurrency,
            ),
            messages = dao.allMessages().map {
                MessageData(it.sender, it.sim, it.body, it.receivedAt, it.outgoing, it.read, it.dedupe)
            },
            reminderRules = rules.map {
                RuleData(
                    it.name, it.type, it.source, it.sourcePackage, it.sourceSender, it.enabled,
                    it.matchPattern, it.amountPattern, it.currencyPattern, it.datePattern,
                    it.zonePattern, it.expiryPattern, it.dateFormat, it.dueMode,
                    it.constantDay, it.anchorDay, it.offsetDays,
                    it.recurrence, it.recurrenceEvery, it.anchorDate,
                    it.defaultCurrency, it.skipNext, it.createdAt,
                    it.notes, it.category, it.remindDaysBefore, it.defaultAmount,
                    it.payEnabled, it.paySource, it.paySender, it.payPackage, it.payMatchPattern,
                    it.payAmountPattern, it.payMatchAmount,
                )
            },
            // Bills carry their rule's *name* rather than its id: ids are reassigned on restore, so
            // a stored id would point at whatever rule happened to land in that row. The name is
            // what survives a round trip and is what re-links them on the way back in.
            bills = (billDao.active() + billDao.history(limit = Int.MAX_VALUE)).map { b ->
                BillData(
                    ruleName = rules.firstOrNull { it.id == b.ruleId }?.name ?: "",
                    name = b.name, dueAt = b.dueAt, amount = b.amount, currency = b.currency,
                    amountRaw = b.amountRaw, dueRaw = b.dueRaw, status = b.status,
                    sourceKey = b.sourceKey, createdAt = b.createdAt, settledAt = b.settledAt,
                    notes = b.notes, category = b.category, remindDaysBefore = b.remindDaysBefore,
                    issuedAt = b.issuedAt, settleKey = b.settleKey, autoSettled = b.autoSettled,
                    editedFields = b.editedFields,
                )
            },
            parking = (parkingDao.active() + parkingDao.history(limit = Int.MAX_VALUE)).map {
                ParkingData(it.name, it.zone, it.expiresAt, it.active, it.renewals, it.startedAt, it.endedAt)
            },
        )
        return json.encodeToString(payload)
    }

    /** Replaces settings + message history from a backup JSON string (call off the main thread). */
    fun import(context: Context, jsonText: String) {
        val payload = json.decodeFromString<Payload>(jsonText)
        // Dates from a file older than VERSION 3 are the old phone's local midnight; convert them as
        // the v13 → v14 database migration does (the currency's zone, else nearest midnight).
        val home by lazy { com.noti.logger.reminder.FxRates.home(context) }
        val legacy = payload.version < 3
        fun legacyDate(ms: Long, currency: String): Long =
            if (!legacy) ms
            else com.noti.logger.reminder.DueDates.legacyToMs(
                ms,
                com.noti.logger.reminder.DueDates.zonesForCurrency(com.noti.logger.reminder.FxRates.isoOf(currency, home)),
                java.time.ZoneId.systemDefault(),
            )
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
            s.chatRetentionDays = chatRetentionDays
            firebaseConfigJson?.takeIf { it.isNotBlank() }?.let { s.firebaseConfigJson = it }
            otpCopyEnabled?.let { s.otpCopyEnabled = it }
            suppressSystemNotifActions?.let { s.suppressSystemNotifActions = it }
            heartbeatEnabled?.let { s.heartbeatEnabled = it }
            companionStatusEnabled?.let { s.companionStatusEnabled = it }
            companionAlertsEnabled?.let { s.companionAlertsEnabled = it }
            companionBatteryPct?.let { s.companionBatteryPct = it }
            companionWifiLossMinutes?.let { s.companionWifiLossMinutes = it }
            companionPollSeconds?.let { s.companionPollSeconds = it }
            companionStatusLabels?.let { s.companionStatusLabels = it }
            reminderEnabled?.let { s.reminderEnabled = it }
            reminderScanHours?.let { s.reminderScanHours = it }
            reminderHour?.let { s.reminderHour = it }
            reminderMinute?.let { s.reminderMinute = it }
            reminderDaysBefore?.let { s.reminderDaysBefore = it }
            reminderSpacingMinutes?.let { s.reminderSpacingMinutes = it }
            parkingLeadMinutes?.let { s.parkingLeadMinutes = it }
            // Only a mode this version knows - a newer file's unknown mode keeps the current one.
            parkingAlarmMode?.takeIf { m -> runCatching { com.noti.logger.reminder.ParkingAlarmMode.valueOf(m) }.isSuccess }
                ?.let { s.parkingAlarmMode = it }
            reminderHistoryDays?.let { s.reminderHistoryDays = it }
            remindersCompact?.let { s.remindersCompact = it }
            remindersShowTotal?.let { s.remindersShowTotal = it }
            defaultCurrency?.let { s.defaultCurrency = it; s.fxAttemptAtMs = 0L }
        }
        val db = NotiDatabase.get(context)
        val dao = db.relayedMessageDao()
        val ruleDao = db.reminderRuleDao()
        val billDao = db.billOccurrenceDao()
        val parkingDao = db.parkingSessionDao()
        dao.replaceAll(
            payload.messages.map {
                RelayedMessageEntity(
                    sender = it.sender, sim = it.sim, body = it.body, receivedAt = it.receivedAt,
                    outgoing = it.outgoing, read = it.read, dedupe = it.dedupe,
                )
            }
        )
        // Row ids were just reassigned, so the Reminder scan's id watermark means nothing now; it
        // falls back to its time window until the next scan sets it again.
        Settings.get(context).lastReminderScanSmsId = -1L

        // Reminders are restored only when the backup carries them. An older file has empty lists,
        // and wiping a working rulebook because the backup predates the feature would be the worst
        // possible reading of "restore".
        val hasReminders = payload.reminderRules.isNotEmpty() ||
            payload.bills.isNotEmpty() || payload.parking.isNotEmpty()
        if (hasReminders) {
            ruleDao.deleteAll()
            billDao.deleteAll()
            parkingDao.deleteAll()

            // Rules first, keeping name -> new id, because ids are reassigned here and a bill's
            // stored rule id would otherwise point at whatever rule landed in that row.
            val idByName = HashMap<String, Long>()
            for (r in payload.reminderRules) {
                val id = ruleDao.insert(
                    ReminderRuleEntity(
                        name = r.name, type = r.type, source = r.source,
                        sourcePackage = r.sourcePackage, sourceSender = r.sourceSender, enabled = r.enabled,
                        matchPattern = r.matchPattern, amountPattern = r.amountPattern,
                        currencyPattern = r.currencyPattern, datePattern = r.datePattern,
                        zonePattern = r.zonePattern, expiryPattern = r.expiryPattern,
                        dateFormat = r.dateFormat, dueMode = r.dueMode,
                        constantDay = r.constantDay, anchorDay = r.anchorDay, offsetDays = r.offsetDays,
                        recurrence = r.recurrence, recurrenceEvery = r.recurrenceEvery, anchorDate = legacyDate(r.anchorDate, r.defaultCurrency),
                        defaultCurrency = r.defaultCurrency, skipNext = r.skipNext, createdAt = r.createdAt,
                        notes = r.notes, category = r.category, remindDaysBefore = r.remindDaysBefore,
                        defaultAmount = r.defaultAmount,
                        payEnabled = r.payEnabled, paySource = r.paySource, paySender = r.paySender,
                        payPackage = r.payPackage, payMatchPattern = r.payMatchPattern,
                        payAmountPattern = r.payAmountPattern, payMatchAmount = r.payMatchAmount,
                    )
                )
                if (id > 0) idByName[r.name] = id
            }
            for (b in payload.bills) {
                billDao.insert(
                    BillOccurrenceEntity(
                        ruleId = idByName[b.ruleName] ?: 0,
                        name = b.name, dueAt = legacyDate(b.dueAt, b.currency), amount = b.amount, currency = b.currency,
                        amountRaw = b.amountRaw, dueRaw = b.dueRaw, status = b.status,
                        sourceKey = b.sourceKey, createdAt = b.createdAt, settledAt = b.settledAt,
                        notes = b.notes, category = b.category, remindDaysBefore = b.remindDaysBefore,
                        issuedAt = b.issuedAt, settleKey = b.settleKey, autoSettled = b.autoSettled,
                        editedFields = b.editedFields,
                    )
                )
            }
            for (p in payload.parking) {
                parkingDao.insert(
                    ParkingSessionEntity(
                        name = p.name, zone = p.zone, expiresAt = p.expiresAt,
                        active = p.active, renewals = p.renewals,
                        startedAt = p.startedAt, endedAt = p.endedAt,
                    )
                )
            }
        }
        reapply(context)
    }

    /**
     * What app start normally does with these settings, done now: a restore can switch Reminders or
     * the heartbeat on or off, and brings back bills whose alarms were never set on this phone -
     * without this they'd stay unarmed until the app next restarted.
     */
    private fun reapply(context: Context) {
        val s = Settings.get(context)
        try {
            if (s.reminderEnabled) {
                com.noti.logger.reminder.ReminderNotifier.ensureChannels(context)
                com.noti.logger.work.ReminderScanWorker.applyFromSettings(context)
                com.noti.logger.reminder.ReminderScheduler.rebuildAll(context)
            } else {
                com.noti.logger.work.ReminderScanWorker.cancelPeriodic(context)
            }
            if (s.role != null && com.noti.logger.push.Heartbeat.enabled(context)) {
                com.noti.logger.push.Heartbeat.baselineIfNeeded(context)
                com.noti.logger.work.HeartbeatWorker.schedulePeriodic(context)
            } else {
                com.noti.logger.work.HeartbeatWorker.cancel(context)
            }
        } catch (e: Exception) {
            // Scheduling is best effort here; app start re-applies it all anyway.
        }
    }
}
