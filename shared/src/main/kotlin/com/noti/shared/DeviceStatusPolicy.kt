package com.noti.shared

/**
 * Shared rules for companion device-status monitoring (see [WireMessage.StatusReport]). Main owns
 * the thresholds and pushes them to the companion as a [WireMessage.StatusConfig]; the companion
 * evaluates them locally, because the alerts must still fire when Main's app isn't running. All the
 * decisions that both sides must agree on live here as pure functions so they can be unit-tested
 * and stay identical on both devices.
 *
 * Two cadences, deliberately different:
 *  - **Polling** (signal, data, VPN, everything on the readout) runs only while Main's Status tab is
 *    on screen. Main opens a short subscription window ([SUBSCRIPTION_SECONDS]) and renews it every
 *    [RENEW_SECONDS] while visible; the window expiring is what stops the companion, so a Main that
 *    is force-stopped or loses network can never leave the companion streaming forever.
 *  - **Alerts** (battery below threshold, wifi down too long) are companion-initiated and run
 *    regardless of what Main is doing, so they arrive when the user isn't looking - which is the
 *    whole point of an alert.
 */
object DeviceStatusPolicy {

    // ---- Polling (foreground only) ----

    /** How often the companion sends a snapshot while a subscription is open. */
    const val DEFAULT_POLL_SECONDS = 10

    /** Bounds on the pushed poll interval: fast enough to feel live, slow enough not to burn the
     *  companion's battery or FCM quota if a bad/hostile config asks for a 1-second firehose. */
    const val MIN_POLL_SECONDS = 5
    const val MAX_POLL_SECONDS = 120

    /** How long one subscription lasts before the companion stops on its own. */
    const val SUBSCRIPTION_SECONDS = 60

    /** How often Main renews while its Status tab is visible - comfortably inside the window above,
     *  so one dropped renewal doesn't produce a visible gap. */
    const val RENEW_SECONDS = 25

    /** Ceiling on an inbound subscription request, so a replayed/hostile one can't pin the companion
     *  into streaming for hours. */
    const val MAX_SUBSCRIPTION_SECONDS = 300

    // ---- Alert thresholds (Main-authored, companion-evaluated) ----

    const val DEFAULT_BATTERY_PCT = 20
    const val MIN_BATTERY_PCT = 1
    const val MAX_BATTERY_PCT = 95

    const val DEFAULT_WIFI_LOSS_MINUTES = 10
    const val MIN_WIFI_LOSS_MINUTES = 1
    const val MAX_WIFI_LOSS_MINUTES = 1440

    /** How far battery must climb back above the threshold before a new low alert can fire. Without
     *  it, a phone hovering at the threshold would alert on every percent that flickers up and down. */
    const val BATTERY_HYSTERESIS_PCT = 5

    /** Notification ids, shared so either side can cancel them. */
    const val BATTERY_NOTIFICATION_ID = 47_002
    const val WIFI_NOTIFICATION_ID = 47_003

    private const val MINUTE_MS = 60_000L

    // ---- Clamping (applied on both sides: Main when saving, companion when applying a push) ----

    fun clampBatteryPct(v: Int): Int = v.coerceIn(MIN_BATTERY_PCT, MAX_BATTERY_PCT)

    fun clampWifiLossMinutes(v: Int): Int = v.coerceIn(MIN_WIFI_LOSS_MINUTES, MAX_WIFI_LOSS_MINUTES)

    fun clampPollSeconds(v: Int): Int = v.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)

    fun clampSubscriptionSeconds(v: Int): Int = v.coerceIn(0, MAX_SUBSCRIPTION_SECONDS)

    // ---- Subscription window ----

    /** Deadline (companion's own clock) for a subscription of [forSeconds], requested at [nowMs].
     *  Relative rather than absolute on purpose: the two phones' clocks need not agree. */
    fun deadlineFor(forSeconds: Int, nowMs: Long): Long =
        if (forSeconds <= 0) 0L else nowMs + clampSubscriptionSeconds(forSeconds) * 1000L

    fun subscriptionActive(deadlineMs: Long, nowMs: Long): Boolean = deadlineMs > nowMs

    // ---- Battery ----

    /** What a battery reading means for alerting, given whether we're currently [armed] (i.e. no
     *  alert is outstanding for this low episode). */
    enum class BatteryDecision {
        /** Nothing to say. */
        NONE,

        /** Crossed below the threshold and we hadn't alerted yet - alert and disarm. */
        ALERT,

        /** Climbed back clear of the threshold (or went on charge) - re-arm for the next episode. */
        RECOVERED,
    }

    /**
     * [pct] < 0 means "unknown" and is always [BatteryDecision.NONE]. Putting the phone on charge
     * counts as recovery even below the threshold: the situation is already being fixed, and a user
     * who plugged in doesn't want the warning to keep firing on the way back up.
     */
    fun batteryDecision(pct: Int, charging: Boolean, thresholdPct: Int, armed: Boolean): BatteryDecision = when {
        pct < 0 -> BatteryDecision.NONE
        charging -> if (armed) BatteryDecision.NONE else BatteryDecision.RECOVERED
        pct <= thresholdPct && armed -> BatteryDecision.ALERT
        pct >= thresholdPct + BATTERY_HYSTERESIS_PCT && !armed -> BatteryDecision.RECOVERED
        else -> BatteryDecision.NONE
    }

    // ---- Wifi ----

    /** True once wifi has been down (since [lostAtMs], 0 = up) for at least [thresholdMinutes]. */
    fun wifiAlertDue(lostAtMs: Long, nowMs: Long, thresholdMinutes: Int): Boolean =
        lostAtMs > 0L && nowMs - lostAtMs >= thresholdMinutes * MINUTE_MS

    /** Milliseconds left before [wifiAlertDue] turns true; 0 when it already is (or wifi is up). */
    fun wifiAlertDelayMs(lostAtMs: Long, nowMs: Long, thresholdMinutes: Int): Long {
        if (lostAtMs <= 0L) return 0L
        return (lostAtMs + thresholdMinutes * MINUTE_MS - nowMs).coerceAtLeast(0L)
    }

    /** Whole minutes wifi has been down, for the report's `wifiDownMinutes` field. */
    fun downMinutes(lostAtMs: Long, nowMs: Long): Int =
        if (lostAtMs <= 0L) 0 else ((nowMs - lostAtMs) / MINUTE_MS).coerceAtLeast(0L).toInt()

    // ---- Readout freshness (Main's card) ----

    /**
     * How many expected updates have gone missing, given a subscription polling every
     * [pollSeconds]. 0 while the latest one is still current.
     *
     * [receivedAtMs] is Main's *own* clock reading when the report arrived, never the companion's
     * `atMs`: the two phones' clocks are independent, and a companion running a few minutes fast or
     * slow would otherwise make every fresh report look missed (or every missed one look fresh).
     */
    fun missedUpdates(receivedAtMs: Long, nowMs: Long, pollSeconds: Int): Int {
        if (receivedAtMs <= 0L) return 0
        val interval = clampPollSeconds(pollSeconds) * 1000L
        val elapsed = nowMs - receivedAtMs
        if (elapsed < interval) return 0
        return (elapsed / interval).toInt()
    }

    /**
     * Whether the readout should be flagged as no longer trustworthy.
     *
     * The first miss is absorbed in silence and the last known values keep showing: one dropped FCM
     * push is unremarkable, and blanking a perfectly good reading over it would be worse than
     * briefly showing one that is a few seconds old. It is the *second* consecutive miss - the
     * retry having failed too - that means something is actually wrong, and only then is the
     * readout marked.
     *
     * This deliberately stops short of declaring the companion offline. That is the heartbeat's
     * job, it already warns with its own banner and notification, and two subsystems shouting the
     * same thing in different words on one screen helps nobody.
     */
    fun readoutSuspect(receivedAtMs: Long, nowMs: Long, pollSeconds: Int): Boolean =
        missedUpdates(receivedAtMs, nowMs, pollSeconds) >= MISSES_BEFORE_WARNING

    /** Misses tolerated before the readout is marked. One retry, then say so. */
    const val MISSES_BEFORE_WARNING = 2
}
