package com.noti.sender

import android.content.Context

/** Lets the app keep its launch-time appearance cache current when the companion's own Appearance
 *  screen (in this library) changes the theme. */
object AppearanceHooks {
    @Volatile
    var onChanged: (Context) -> Unit = {}
}
