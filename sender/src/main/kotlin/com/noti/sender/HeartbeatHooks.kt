package com.noti.sender

import android.content.Context

/** Heartbeat actions the :sender screens trigger but the app implements (see Heartbeat). */
object HeartbeatHooks {
    /** This phone's heartbeat was switched off: tell the peer so it stops expecting beats. */
    @Volatile
    var onSwitchedOff: (Context) -> Unit = {}
}
