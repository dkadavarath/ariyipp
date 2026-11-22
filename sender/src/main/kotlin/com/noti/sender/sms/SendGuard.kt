package com.noti.sender.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.noti.sender.R
import com.noti.sender.config.SenderSettings

/**
 * The companion's own limits on what Main can make its SIM send. Whoever holds Main's keys (the
 * relay key, the service-account key and this phone's token, all of which sit on Main and in its
 * backup) can command an SMS to anyone. These cap the damage:
 *  - a rolling send limit, per hour and per day (default 20 and 50), and
 *  - optionally, only numbers that have texted this phone.
 * Both are set on this phone only, never from Main. A refused send is acked FAILED with the reason
 * and raises a notification here, which doubles as the warning that something is wrong.
 */
object SendGuard {

    private const val HOUR_MS = 60 * 60_000L
    private const val DAY_MS = 24 * HOUR_MS
    private const val CHANNEL = "ariy_send_guard"
    private const val NOTIF_ID = 4714 // 4712 is the webhook-config notice, 4713 the peer-version one

    /** Why a send over the limits is refused, or null when it may go. Pure. */
    fun limitReason(sentAt: List<Long>, now: Long, perHour: Int, perDay: Int): String? {
        val lastDay = sentAt.count { now - it in 0 until DAY_MS }
        val lastHour = sentAt.count { now - it in 0 until HOUR_MS }
        return when {
            lastHour >= perHour -> "hourly send limit reached ($perHour) - not sent"
            lastDay >= perDay -> "daily send limit reached ($perDay) - not sent"
            else -> null
        }
    }

    /**
     * Whether [to] is the same number (or sender name) as one of [known]. Numbers are compared whole,
     * in international form, reading local ones ("050...") as [countryIso]'s. Not
     * PhoneNumberUtils.compare: that matches on the last 7 digits, so a number abroad sharing them
     * with someone who texted this phone passed the allowlist.
     */
    fun isKnown(to: String, known: Collection<String>, countryIso: String?): Boolean {
        val target = e164(to, countryIso)
        return known.any { k ->
            k.equals(to, ignoreCase = true) ||
                (target != null && target == e164(k, countryIso)) ||
                digits(k).let { it.length >= 7 && it == digits(to) }
        }
    }

    private fun digits(s: String) = s.filter { it.isDigit() }

    private fun e164(number: String, iso: String?): String? {
        if (number.any { it.isLetter() }) return null
        return try { PhoneNumberUtils.formatNumberToE164(number, (iso ?: "").uppercase()) } catch (e: Exception) { null }
    }

    /** The country local numbers on this phone belong to: the SIM's, else the network's. */
    private fun countryIso(context: Context): String? = try {
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    /**
     * Checks a command-send to [to] against the limits and the allowlist. Returns the refusal reason,
     * or null - in which case the send is counted, so call this only right before sending.
     */
    @Synchronized
    fun check(context: Context, to: String, now: Long = System.currentTimeMillis()): String? {
        val s = SenderSettings.get(context)
        if (s.allowlistOnly && !hasTexted(context, to)) {
            return "recipient not on the allowlist (only numbers that texted the companion)"
        }
        val recent = s.commandSendTimes.filter { now - it in 0 until DAY_MS }
        limitReason(recent, now, s.sendLimitPerHour, s.sendLimitPerDay)?.let { return it }
        s.commandSendTimes = recent + now
        return null
    }

    /** Whether [to] has ever texted this phone, from the SMS inbox. */
    private fun hasTexted(context: Context, to: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        // Narrow by the last digits, then compare properly (+971 50... vs 050...).
        val digits = to.filter { it.isDigit() }
        val tail = if (digits.length >= 7) digits.takeLast(7) else null
        val (sel, args) = if (tail != null) {
            "${Telephony.Sms.ADDRESS} LIKE ?" to arrayOf("%$tail")
        } else {
            "${Telephony.Sms.ADDRESS} = ? COLLATE NOCASE" to arrayOf(to)
        }
        val known = ArrayList<String>()
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS), sel, args, null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { known.add(it) } }
        } catch (e: Exception) {
            return false
        }
        return isKnown(to, known, countryIso(context))
    }

    /** Tells the person holding the companion that a send was refused. */
    fun notifyRefused(context: Context, to: String, reason: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Refused sends", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "SMS Main asked for that this phone's limits refused" }
            )
        }
        val text = context.getString(R.string.send_refused_text, to, reason)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.send_refused_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) } catch (e: SecurityException) { }
    }
}
