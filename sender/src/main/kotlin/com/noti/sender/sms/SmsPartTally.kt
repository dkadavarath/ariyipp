package com.noti.sender.sms

import android.content.Context

/**
 * Counts the per-part callbacks of a long SMS so the message is acked once, for all of it: "sent"
 * when every part was sent, "failed" the moment one wasn't, "delivered" when every part's delivery
 * report is in. Kept on disk: the reports can come minutes apart, across a process restart.
 */
// commit, not apply, throughout: callers run off the main thread, and a tally lost to a process
// death between two parts would ack a message twice or never.
@android.annotation.SuppressLint("ApplySharedPref")
internal object SmsPartTally {

    private const val PREFS = "sms_part_tally"
    private const val KEEP_MS = 3L * 24 * 60 * 60_000L

    /** A new send of [msgId] starts: forget any earlier attempt's tally. */
    @Synchronized
    fun reset(context: Context, msgId: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("${SmsSender.ACTION_SMS_SENT}:$msgId").remove("${SmsSender.ACTION_SMS_DELIVERED}:$msgId").commit()
    }

    /** Records [part] of [parts]; true when this callback is the one that decides the message. */
    @Synchronized
    fun record(context: Context, action: String, msgId: Long, part: Int, parts: Int, ok: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$action:$msgId"
        val raw = prefs.getString(key, null)
        // "<first seen>|<done>|<part,part,...>"; done = this message's ack already went out.
        val fields = raw?.split('|')
        val since = fields?.getOrNull(0)?.toLongOrNull() ?: now
        if (fields?.getOrNull(1) == "1") return false
        val seen = fields?.getOrNull(2).orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
        seen += part.toString()
        val decided = !ok || seen.size >= parts
        val edit = prefs.edit()
        edit.putString(key, "$since|${if (decided) "1" else "0"}|${seen.joinToString(",")}")
        // Forget what's long settled, or never will be (a part whose report never came).
        for ((k, v) in prefs.all) {
            val t = (v as? String)?.substringBefore('|')?.toLongOrNull() ?: continue
            if (now - t > KEEP_MS) edit.remove(k)
        }
        edit.commit()
        return decided
    }
}
