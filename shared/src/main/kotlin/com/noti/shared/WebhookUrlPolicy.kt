package com.noti.shared

import java.net.URL

/**
 * Shared https/loopback policy for webhook URLs (noti's own webhook and the companion's n8n
 * webhook), used both when saving one (fail fast, with a clear reason) and when actually sending
 * to it (defense in depth if a bad one got saved anyway - e.g. a restored backup from before this
 * validation existed, or a config pushed from a peer). Mirrors the exceptions
 * network_security_config.xml grants for local dev/testing, so this is never stricter than the
 * policy already in effect at the OS level.
 */
object WebhookUrlPolicy {
    private val LOOPBACK = setOf("localhost", "127.0.0.1")

    /** Also allow plain http to the emulator's host (10.0.2.2): debug builds set this at start.
     *  On a real phone that address is an ordinary private-network one, so release builds don't. */
    @Volatile var allowEmulatorHost = false

    fun isAllowed(url: String): Boolean {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            return false
        }
        return parsed.protocol.equals("https", ignoreCase = true) ||
            (parsed.protocol.equals("http", ignoreCase = true) &&
                (parsed.host in LOOPBACK || (allowEmulatorHost && parsed.host == "10.0.2.2")))
    }
}
