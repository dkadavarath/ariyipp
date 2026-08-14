package com.noti.shared

/**
 * Shared rules for the liveness heartbeat (see [WireMessage.Heartbeat]). Both roles send a beat on
 * this cadence and each tracks the peer's last-seen time; when too many beats are missed the peer is
 * considered disconnected. Pure logic so it can be unit-tested and kept identical on both sides.
 */
object HeartbeatPolicy {

    /** How often each device sends a beat to its peer. 15 min = WorkManager's periodic minimum. */
    const val INTERVAL_MINUTES = 15L

    /** No beat within this window ⇒ peer is disconnected. 3× the interval tolerates Doze/jitter. */
    const val STALE_AFTER_MINUTES = 45L

    /** Notification id for the "peer disconnected" warning, shared so either side can cancel it. */
    const val NOTIFICATION_ID = 47_001

    /** Broadcast action for the notification/status "Retry now" button (app registers the receiver). */
    const val ACTION_RETRY = "com.noti.logger.action.HEARTBEAT_RETRY"

    private const val MINUTE_MS = 60_000L

    val staleAfterMs: Long get() = STALE_AFTER_MINUTES * MINUTE_MS

    /**
     * True when the peer should be treated as disconnected: we've had a baseline ([lastBeatAtMs] > 0)
     * and haven't heard from it within [STALE_AFTER_MINUTES]. A zero baseline (never started / not
     * paired) is never stale — the clock only starts once pairing sets it.
     */
    fun isStale(lastBeatAtMs: Long, nowMs: Long): Boolean =
        lastBeatAtMs > 0L && nowMs - lastBeatAtMs > staleAfterMs

    /** Rough "next automatic check" delay from now, for the ETA line in the warning. */
    val retryEtaMinutes: Long get() = INTERVAL_MINUTES
}
