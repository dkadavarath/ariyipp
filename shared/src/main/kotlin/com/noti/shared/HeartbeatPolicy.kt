package com.noti.shared

/**
 * Shared rules for the liveness heartbeat (see [WireMessage.Heartbeat]). Both roles send a beat on
 * this cadence and each tracks the peer's last-seen time; when too many beats are missed the peer is
 * considered disconnected. Pure logic so it can be unit-tested and kept identical on both sides.
 */
object HeartbeatPolicy {

    /** Every beat shares this FCM collapse slot: only the newest waits for an offline peer. */
    const val COLLAPSE_KEY = "heartbeat"

    /** A beat older than this says nothing about liveness now, so FCM may drop it. */
    const val TTL_SECONDS = 30 * 60


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

    /**
     * Going stale is only a suspicion: the routine beat travels at normal priority and both phones
     * defer their 15-min work under Doze, so a healthy peer can easily miss the window. Before
     * warning, the device sends a high-priority force-check and waits this long for any reply.
     */
    const val PROBE_WAIT_MINUTES = 3L

    val probeWaitMs: Long get() = PROBE_WAIT_MINUTES * MINUTE_MS

    /** Any authenticated push from the peer proves it alive; record it at most this often so a
     *  status stream (one report every few seconds) doesn't rewrite encrypted prefs each time. */
    const val ALIVE_WRITE_THROTTLE_MS = 60_000L

    /** True when a reply (a beat, or any other traffic) arrived at or after the force-check sent at
     *  [probeAtMs] - the peer is alive and the warning must not be shown. */
    fun answeredSince(lastBeatAtMs: Long, probeAtMs: Long): Boolean =
        probeAtMs > 0L && lastBeatAtMs >= probeAtMs

    /** Whether a non-heartbeat push should refresh the last-seen time, given the stored one. */
    fun shouldRecordAlive(lastBeatAtMs: Long, nowMs: Long): Boolean =
        nowMs - lastBeatAtMs >= ALIVE_WRITE_THROTTLE_MS || nowMs < lastBeatAtMs

    /** Rough "next automatic check" delay from now, for the ETA line in the warning. */
    val retryEtaMinutes: Long get() = INTERVAL_MINUTES
}
