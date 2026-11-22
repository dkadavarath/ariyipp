package com.noti.shared

/**
 * Replay checks for the messages that aren't commands (commands have their own, see the companion's
 * SmsCommandHandler). Anyone who can push to the Firebase project can resend a captured ciphertext:
 * it still decrypts, so the send time stamped inside it (Wire.encodeFor) is what tells an old copy
 * from a new message.
 *
 * The test is order, not age: a message counts only when it was sent after the newest one already
 * taken from that phone (the "mark"). Both stamps come from the *sender's* clock, so neither the
 * time zone (stamps are UTC milliseconds) nor a sender clock that is simply wrong matters. An age
 * limit against this phone's clock would refuse everything from a phone set an hour off.
 */
object ReplayGuard {

    /** Clocks that agree to within this are taken as agreeing. */
    const val SKEW_MS = 2 * 60_000L

    /**
     * The newest stamp taken from a peer ([sentAt], its clock), when it was taken ([seenAt], this
     * phone's clock), and a [floor]: after a clock correction (see [isNew]) the stamps sent while
     * the peer ran ahead stay suspect until this phone's clock passes the highest of them; 0 = none.
     */
    data class Mark(val sentAt: Long, val seenAt: Long, val floor: Long = 0)

    /**
     * Whether a message stamped [sentAt] is new, given the [mark]. One exception to strict order: a
     * peer whose clock ran ahead and was then corrected stamps "earlier" than its own mark from then
     * on, and would never be heard again. That is let through when the mark was set while the peer
     * was ahead and the new stamp now agrees with this phone's clock. Taking it lowers the mark, so
     * the old, ahead stamps would pass again as "newer" - which is what the [Mark.floor] is for: a
     * stamp at or below it counts only if it, too, agrees with this phone's clock.
     */
    fun isNew(sentAt: Long, mark: Mark, now: Long): Boolean {
        if (sentAt <= 0) return false
        val agrees = kotlin.math.abs(sentAt - now) <= SKEW_MS
        if (sentAt > mark.sentAt) return mark.floor <= 0 || sentAt > mark.floor || agrees
        val wasAhead = mark.sentAt - mark.seenAt > SKEW_MS
        return wasAhead && agrees
    }

    /** The mark after taking a message stamped [sentAt] (one [isNew] allowed) at [now]. */
    fun next(mark: Mark, sentAt: Long, now: Long): Mark = when {
        // In order: past the floor, the suspect stamps are all behind it.
        sentAt > mark.sentAt -> Mark(sentAt, now, floor = if (sentAt > mark.floor) 0 else mark.floor)
        // The clock-correction exception: remember how far ahead the peer had stamped.
        else -> Mark(sentAt, now, floor = maxOf(mark.floor, mark.sentAt))
    }
}
