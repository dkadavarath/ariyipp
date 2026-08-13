package com.noti.sender

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Remembers which messages have already been relayed to each destination, so the missed-SMS sync
 * (and worker retries) don't re-send something the live relay already handled. ippu dedups on its
 * own, but the n8n webhook has no dedup — this is what keeps it from getting duplicates.
 *
 * Keyed on a timestamp-free content signature ("<leg>:<hash of from|body>"), because the SMSC time
 * the live relay sees and the inbox DATE_SENT the sync reads often differ (DATE_SENT is 0 on many
 * carriers), which used to make the two paths compute different keys and slip a duplicate through.
 * A recency [WINDOW] lets a genuinely-repeated identical message through later while still collapsing
 * the live+sync delivery of the same one. Recorded only after a leg succeeds, so a transient failure
 * still retries. Bounded FIFO in plain prefs (the keys are SHA hashes, not secrets).
 */
object RelayDedupe {

    private const val PREFS = "noti_relay_dedupe"
    private const val KEY = "sent"
    private const val CAP = 4000
    private val WINDOW = TimeUnit.HOURS.toMillis(24)

    // key -> last-sent epoch ms. Loaded from disk once per process; only a change touches disk.
    private var cache: LinkedHashMap<String, Long>? = null

    /** True if [contentKey] was delivered to [leg] within the recency window. */
    @Synchronized
    fun alreadySent(context: Context, leg: String, contentKey: String): Boolean {
        val at = entries(context)["$leg:$contentKey"] ?: return false
        return System.currentTimeMillis() - at < WINDOW
    }

    /** Marks [contentKey] as delivered to [leg] now. */
    @Synchronized
    fun record(context: Context, leg: String, contentKey: String) {
        val map = entries(context)
        val k = "$leg:$contentKey"
        map.remove(k) // re-insert so recently-touched entries sit at the newest end
        map[k] = System.currentTimeMillis()
        while (map.size > CAP) map.keys.iterator().let { it.next(); it.remove() }
        persist(context, map)
    }

    /** Clears everything (in-memory cache and disk). */
    @Synchronized
    fun clear(context: Context) {
        cache = null
        prefs(context).edit().clear().apply()
    }

    private fun entries(context: Context): LinkedHashMap<String, Long> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Long>()
        val raw = prefs(context).getString(KEY, "").orEmpty()
        if (raw.isNotEmpty()) {
            for (line in raw.split('\n')) {
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) continue
                val t = line.substring(tab + 1).toLongOrNull() ?: continue
                map[line.substring(0, tab)] = t
            }
        }
        cache = map
        return map
    }

    private fun persist(context: Context, map: LinkedHashMap<String, Long>) {
        val sb = StringBuilder()
        for ((k, t) in map) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(k).append('\t').append(t)
        }
        prefs(context).edit().putString(KEY, sb.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
