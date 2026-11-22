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

    /**
     * [address] as it may appear in the log: a phone number shows only its last 4 digits
     * ("…4321"), so a shared or screenshotted log doesn't hand out whom you text. Named senders
     * (a bank's "VM-HDFCBK") stay as they are: they're not anyone's number, and they're what
     * tells you which rule a line is about.
     */
    fun mask(address: String): String {
        val digits = address.count { it.isDigit() }
        if (digits < 5 || address.any { it.isLetter() }) return address
        return "…" + address.filter { it.isDigit() }.takeLast(4)
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
