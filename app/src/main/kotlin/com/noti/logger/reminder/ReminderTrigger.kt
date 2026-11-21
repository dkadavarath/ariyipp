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
    @Volatile private var watched: Set<String> = emptySet()
    @Volatile private var watchedAtMs = 0L

    /**
     * Whether an enabled rule reads notifications from [pkg]. Capture skips ongoing notifications as
     * noise (media players, timers), but a parking app shows its running session as exactly that, so
     * those apps are let through. Cached for a minute: ongoing notifications update constantly, and
     * this is asked for every update. Blocking on a cache miss - call off the main thread.
     */
    fun watchesApp(context: Context, pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - watchedAtMs !in 0 until WATCHED_TTL_MS) {
            watched = if (!Settings.get(context).reminderEnabled) emptySet() else try {
                NotiDatabase.get(context).reminderRuleDao().enabled().flatMap { r ->
                    listOfNotNull(
                        r.sourcePackage.takeIf { r.source == SourceType.NOTIFICATION.name && it.isNotBlank() },
                        r.payPackage.takeIf { paysWatched(r) && r.paySource == SourceType.NOTIFICATION.name && it.isNotBlank() },
                    )
                }.toSet()
            } catch (e: Exception) { emptySet() }
            watchedAtMs = now
        }
        return pkg in watched
    }

    /** A rule changed: re-read the watched apps on the next ask. */
    fun invalidateWatched() { watchedAtMs = 0L }

    private fun paysWatched(r: ReminderRuleEntity) =
        r.payEnabled == 1 && r.payMatchPattern.isNotBlank() && r.type == RuleType.BILL.name
}
