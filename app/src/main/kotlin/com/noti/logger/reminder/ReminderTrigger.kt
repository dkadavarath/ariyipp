package com.noti.logger.reminder

import android.content.Context
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.work.ReminderScanWorker

/**
 * Runs the rulebook the moment something arrives that a rule could be about, instead of leaving it
 * for the next scheduled scan. The check here is only the cheap gate - is this app, or this SMS
 * sender, named by an enabled rule (for its bills or for its payments)? Whether the message really
 * is a bill, a payment or a parking ticket is still decided by the scan, the same code the periodic
 * run and "Calculate now" use, so there is one set of rules for all three.
 *
 * Everything else - the chat from a friend, the 40th WhatsApp notification - stops at the gate and
 * costs one small query of the rules table.
 */
object ReminderTrigger {

    /** A notification from [pkg] was captured. Blocking - call off the main thread. */
    fun onNotification(context: Context, pkg: String) {
        if (wants(context) { matchesNotification(it, pkg) }) ReminderScanWorker.runForEvent(context)
    }

    /** An SMS from [sender] was stored. Blocking - call off the main thread. */
    fun onSms(context: Context, sender: String) {
        if (wants(context) { matchesSms(it, sender) }) ReminderScanWorker.runForEvent(context)
    }

    private inline fun wants(context: Context, test: (List<ReminderRuleEntity>) -> Boolean): Boolean {
        if (!Settings.get(context).reminderEnabled) return false
        val rules = try { NotiDatabase.get(context).reminderRuleDao().enabled() } catch (e: Exception) { return false }
        return test(rules)
    }

    /** Some enabled rule takes its bills, tickets or payments from app [pkg]. */
    fun matchesNotification(rules: List<ReminderRuleEntity>, pkg: String): Boolean = rules.any { r ->
        (r.source == SourceType.NOTIFICATION.name && r.sourcePackage.isNotBlank() && r.sourcePackage == pkg) ||
            (paysWatched(r) && r.paySource == SourceType.NOTIFICATION.name && r.payPackage.isNotBlank() && r.payPackage == pkg)
    }

    /** Some enabled rule takes its bills, tickets or payments from SMS sender [sender]. The same
     *  substring test the scan applies; a rule with no sender filter reads every SMS. */
    fun matchesSms(rules: List<ReminderRuleEntity>, sender: String): Boolean = rules.any { r ->
        (r.source == SourceType.SMS.name && (r.sourceSender.isBlank() || sender.contains(r.sourceSender, ignoreCase = true))) ||
            (paysWatched(r) && r.paySource == SourceType.SMS.name &&
                (r.paySender.isBlank() || sender.contains(r.paySender, ignoreCase = true)))
    }

    // ---- Which apps the rulebook watches, for capture's ongoing-notification exception ----

    private const val WATCHED_TTL_MS = 60_000L
    /** Enabled rules that read notifications, by the package they read. */
    @Volatile private var watched: Map<String, List<ReminderRuleEntity>> = emptyMap()
    @Volatile private var watchedAtMs = 0L

    private fun watchedRules(context: Context): Map<String, List<ReminderRuleEntity>> {
        val now = System.currentTimeMillis()
        if (now - watchedAtMs !in 0 until WATCHED_TTL_MS) {
            watched = if (!Settings.get(context).reminderEnabled) emptyMap() else try {
                val byPkg = HashMap<String, MutableList<ReminderRuleEntity>>()
                for (r in NotiDatabase.get(context).reminderRuleDao().enabled()) {
                    if (r.source == SourceType.NOTIFICATION.name && r.sourcePackage.isNotBlank())
                        byPkg.getOrPut(r.sourcePackage) { mutableListOf() }.add(r)
                    if (paysWatched(r) && r.paySource == SourceType.NOTIFICATION.name && r.payPackage.isNotBlank() &&
                        r.payPackage != r.sourcePackage.takeIf { r.source == SourceType.NOTIFICATION.name })
                        byPkg.getOrPut(r.payPackage) { mutableListOf() }.add(r)
                }
                byPkg
            } catch (e: Exception) { emptyMap() }
            watchedAtMs = now
        }
        return watched
    }

    /**
     * Whether an enabled rule reads notifications from [pkg]. Capture skips ongoing notifications as
     * noise (media players, timers), but a parking app shows its running session as exactly that, so
     * those apps are let through. Cached for a minute: ongoing notifications update constantly, and
     * this is asked for every update. Blocking on a cache miss - call off the main thread.
     */
    fun watchesApp(context: Context, pkg: String): Boolean = pkg in watchedRules(context)

    /**
     * What the rules for [pkg] read out of an ongoing notification's [text], or null when no rule is
     * about it at all. Capture keeps an ongoing update only when this changes: a parking countdown
     * re-posts every minute, and storing (and scanning, and uploading) each tick would be one row a
     * minute for nothing; a renewal changes the expiry, so it still gets through.
     */
    fun ongoingSignature(context: Context, pkg: String, text: String): String? {
        val rules = watchedRules(context)[pkg] ?: return null
        val parts = rules.mapNotNull { r -> signatureOf(r, pkg, text)?.let { "${r.id}:$it" } }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("|")
    }

    private fun signatureOf(r: ReminderRuleEntity, pkg: String, text: String): String? {
        val readsBills = r.source == SourceType.NOTIFICATION.name && r.sourcePackage == pkg
        val bill = if (!readsBills) null else when (r.type) {
            RuleType.PARKING.name -> ReminderExtractor.extractParking(
                text, r.matchPattern, r.zonePattern, r.expiryPattern, r.dateFormat,
            // The resolved time, not the raw text: "valid until 14:30" today and tomorrow are two
            // sessions, and the raw text alone made the second look like the first.
            )?.let { "p ${it.zone} ${it.expiry ?: it.expiryRaw}" }
            else -> ReminderExtractor.extractBill(
                text, r.matchPattern, r.amountPattern, r.currencyPattern, r.datePattern, r.dateFormat,
            )?.let { "b ${it.amountRaw} ${it.currency} ${it.dueDate ?: it.dueDateRaw}" }
        }
        val pay = if (paysWatched(r) && r.paySource == SourceType.NOTIFICATION.name && r.payPackage == pkg &&
            ReminderExtractor.matches(r.payMatchPattern, text)
        ) "$ ${ReminderExtractor.capture(r.payAmountPattern, text)}" else null
        return listOfNotNull(bill, pay).takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** A rule changed: re-read the watched apps on the next ask. */
    fun invalidateWatched() { watchedAtMs = 0L }

    private fun paysWatched(r: ReminderRuleEntity) =
        r.payEnabled == 1 && r.payMatchPattern.isNotBlank() && r.type == RuleType.BILL.name
}
