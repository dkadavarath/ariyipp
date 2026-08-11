package com.noti.sender

import android.content.Context

/**
 * Remembers which messages have already been relayed to each destination, so the missed-SMS sync
 * (and worker retries) don't re-send something the live relay already handled. ippu dedups on its
 * own by content hash, but the n8n webhook has no dedup — this is what keeps it from getting
 * duplicates. A bounded FIFO of "<leg>:<contentKey>" entries in plain prefs (the keys are SHA
 * hashes, not secrets). Recorded only after a leg succeeds, so a transient failure still retries.
 */
object RelayDedupe {

    private const val PREFS = "noti_relay_dedupe"
    private const val KEY = "sent"
    private const val CAP = 6000 // ~3000 messages × 2 legs

    // Loaded from disk once per process, then kept in memory; only a change touches disk. Avoids a
    // full read + split (up to 6000 strings) on every relay's dedup check.
    private var cache: LinkedHashSet<String>? = null

    /** True if [contentKey] was already delivered to [leg] ("fcm" or "n8n"). */
    @Synchronized
    fun alreadySent(context: Context, leg: String, contentKey: String): Boolean =
        entries(context).contains("$leg:$contentKey")

    /** Marks [contentKey] as delivered to [leg]. */
    @Synchronized
    fun record(context: Context, leg: String, contentKey: String) {
        val set = entries(context)
        if (!set.add("$leg:$contentKey")) return
        while (set.size > CAP) set.iterator().let { it.next(); it.remove() }
        prefs(context).edit().putString(KEY, set.joinToString("\n")).apply()
    }

    /** Clears everything (both the in-memory cache and disk). */
    @Synchronized
    fun clear(context: Context) {
        cache = null
        prefs(context).edit().clear().apply()
    }

    private fun entries(context: Context): LinkedHashSet<String> {
        cache?.let { return it }
        val raw = prefs(context).getString(KEY, "").orEmpty()
        return LinkedHashSet<String>(if (raw.isEmpty()) emptyList() else raw.split('\n')).also { cache = it }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
