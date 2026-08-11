package com.noti.shared

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny in-memory diagnostics log so failures are *visible* in-app instead of vanishing to logcat.
 * Records the real outcome of each relay/push/command attempt (HTTP codes, decrypt failures, "not
 * paired", …). Shared so both apps use the same surface. Newest-first snapshot for display.
 */
object Diag {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Notified on every change so an open Diagnostics screen can refresh live. */
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun log(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
        listener?.invoke()
    }

    /** Newest first. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList().asReversed()

    @Synchronized
    fun clear() {
        lines.clear()
        listener?.invoke()
    }
}
