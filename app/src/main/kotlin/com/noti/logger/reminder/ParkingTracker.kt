package com.noti.logger.reminder

import android.content.Context
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ParkingSessionEntity
import com.noti.logger.data.ReminderRuleEntity
import com.noti.shared.Diag
import java.time.LocalDateTime

/**
 * Owns the life of a parking session: start it on the first matching ticket, push its expiry out on
 * every renewal, and end it only when you say so.
 *
 * A session is *not* ended by its expiry passing. That is deliberate: an expired session you
 * haven't dealt with is exactly the one still worth showing, and auto-closing it would quietly
 * remove the evidence that a reminder was missed. Only [end] closes one.
 *
 * Renewals are matched by **zone**, so two cars in two zones are two sessions, and a renewal for a
 * zone already running extends that one instead of opening a second. When the zone couldn't be read
 * we fall back to the rule's single running session - a renewal with an unreadable zone is far more
 * likely to be about the session already open than about a new one, and the alternative (a second
 * session every renewal) is much worse to clean up.
 */
object ParkingTracker {

    /**
     * A parking message matched. Returns true when this changed something (a new session, or an
     * extension) - a re-scan seeing the same ticket again returns false.
     */
    fun onMatch(context: Context, rule: ReminderRuleEntity, zone: String, expiry: LocalDateTime?): Boolean {
        val dao = NotiDatabase.get(context).parkingSessionDao()
        val existing = try {
            dao.activeForZone(rule.id, zone) ?: if (zone.isBlank()) dao.latestActiveForRule(rule.id) else null
        } catch (e: Exception) {
            null
        }

        return if (existing == null) start(context, rule, zone, expiry) else extend(context, existing, zone, expiry)
    }

    private fun start(context: Context, rule: ReminderRuleEntity, zone: String, expiry: LocalDateTime?): Boolean {
        val dao = NotiDatabase.get(context).parkingSessionDao()
        val lead = Settings.get(context).parkingLeadMinutes
        val expiresAt = expiry?.let { ReminderPolicy.toEpochMillis(it) } ?: 0L
        val remindAt = expiry?.let { ReminderPolicy.toEpochMillis(ReminderPolicy.parkingReminderAt(it, lead)) } ?: 0L

        val id = try {
            dao.insert(
                ParkingSessionEntity(
                    ruleId = rule.id,
                    name = rule.name,
                    zone = zone,
                    expiresAt = expiresAt,
                    remindAt = remindAt,
                    active = 1,
                )
            )
        } catch (e: Exception) {
            return false
        }
        if (id <= 0) return false

        Diag.log("parking session started: ${rule.name}${if (zone.isBlank()) "" else " ($zone)"}")
        askForMissingDetails(context, id, rule.name, zone, expiry)
        if (expiry != null) armAlarm(context, id, rule.name, zone, remindAt)
        return true
    }

    private fun extend(context: Context, session: ParkingSessionEntity, zone: String, expiry: LocalDateTime?): Boolean {
        // Nothing new to say: no readable expiry, or one no later than what we already hold. A
        // ticket re-delivered by the same app must not count as a renewal.
        val newExpiryMs = expiry?.let { ReminderPolicy.toEpochMillis(it) } ?: return false
        if (newExpiryMs <= session.expiresAt) return false

        val dao = NotiDatabase.get(context).parkingSessionDao()
        val lead = Settings.get(context).parkingLeadMinutes
        val remindAt = ReminderPolicy.toEpochMillis(ReminderPolicy.parkingReminderAt(expiry, lead))

        // Withdraw the old alarm before arming the new one - under the clock-app backend an alarm
        // is only addressable by its label, so leaving the old one in place would ring at the old
        // expiry with nothing left to cancel it.
        ParkingAlarm.cancel(context, session.id, session.name, session.zone)
        ReminderNotifier.cancelParking(context, session.id)

        val updated = session.copy(
            zone = session.zone.ifBlank { zone },
            expiresAt = newExpiryMs,
            remindAt = remindAt,
            renewals = session.renewals + 1,
            incompleteNotified = 0,
        )
        try { dao.update(updated) } catch (e: Exception) { return false }

        armAlarm(context, updated.id, updated.name, updated.zone, remindAt)
        Diag.log("parking extended: ${updated.name} → ${expiry.toLocalTime()} (renewal ${updated.renewals})")
        return true
    }

    /** End a session for good - the only thing that closes one. */
    fun end(context: Context, sessionId: Long) {
        val dao = NotiDatabase.get(context).parkingSessionDao()
        val session = try { dao.byId(sessionId) } catch (e: Exception) { null } ?: return
        ParkingAlarm.cancel(context, session.id, session.name, session.zone)
        ReminderScheduler.cancelParkingRing(context, session.id)
        ReminderNotifier.cancelParking(context, session.id)
        try { dao.end(sessionId, System.currentTimeMillis()) } catch (e: Exception) { }
        Diag.log("parking session ended: ${session.name}")
    }

    /** Apply a zone/expiry the user filled in by hand after we couldn't read them. */
    fun setDetails(context: Context, sessionId: Long, zone: String, expiry: LocalDateTime?) {
        val dao = NotiDatabase.get(context).parkingSessionDao()
        val session = try { dao.byId(sessionId) } catch (e: Exception) { null } ?: return
        ParkingAlarm.cancel(context, session.id, session.name, session.zone)

        val lead = Settings.get(context).parkingLeadMinutes
        val expiresAt = expiry?.let { ReminderPolicy.toEpochMillis(it) } ?: session.expiresAt
        val remindAt = expiry?.let { ReminderPolicy.toEpochMillis(ReminderPolicy.parkingReminderAt(it, lead)) }
            ?: session.remindAt
        val updated = session.copy(zone = zone, expiresAt = expiresAt, remindAt = remindAt, incompleteNotified = 1)
        try { dao.update(updated) } catch (e: Exception) { return }
        ReminderNotifier.cancelParking(context, session.id)
        armAlarm(context, updated.id, updated.name, updated.zone, remindAt)
    }

    /** Start a session by hand, with no message behind it. */
    fun startManual(context: Context, name: String, zone: String, expiry: LocalDateTime): Long {
        val dao = NotiDatabase.get(context).parkingSessionDao()
        val lead = Settings.get(context).parkingLeadMinutes
        val remindAt = ReminderPolicy.toEpochMillis(ReminderPolicy.parkingReminderAt(expiry, lead))
        val id = try {
            dao.insert(
                ParkingSessionEntity(
                    ruleId = 0,
                    name = name,
                    zone = zone,
                    expiresAt = ReminderPolicy.toEpochMillis(expiry),
                    remindAt = remindAt,
                    incompleteNotified = 1,
                )
            )
        } catch (e: Exception) {
            return 0
        }
        if (id > 0) armAlarm(context, id, name, zone, remindAt)
        return id
    }

    private fun armAlarm(context: Context, sessionId: Long, name: String, zone: String, remindAtMs: Long) {
        if (remindAtMs <= System.currentTimeMillis()) return
        ParkingAlarm.scheduleAt(context, sessionId, name, zone, ReminderPolicy.fromEpochMillis(remindAtMs))
    }

    /**
     * Ask for whatever couldn't be read. Raised once per session (tracked by `incompleteNotified`),
     * because a parking app that re-posts its notification every few minutes would otherwise turn
     * one gap in a regex into a stream of identical demands for attention.
     */
    private fun askForMissingDetails(context: Context, sessionId: Long, name: String, zone: String, expiry: LocalDateTime?) {
        val missingZone = zone.isBlank()
        val missingExpiry = expiry == null
        if (!missingZone && !missingExpiry) return

        val missing = when {
            missingZone && missingExpiry -> context.getString(R.string.rm_missing_both)
            missingZone -> context.getString(R.string.rm_missing_zone)
            else -> context.getString(R.string.rm_missing_expiry)
        }
        ReminderNotifier.showNeedsDetails(context, sessionId, name, missing)
        try {
            val dao = NotiDatabase.get(context).parkingSessionDao()
            dao.byId(sessionId)?.let { dao.update(it.copy(incompleteNotified = 1)) }
        } catch (e: Exception) {
            // best-effort
        }
        Diag.log("parking session needs details: $name ($missing)")
    }
}
