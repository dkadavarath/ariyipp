package com.noti.sender.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import com.noti.sender.config.SenderSettings

/**
 * Screenshot protection (FLAG_SECURE): a protected screen can't be screenshotted or screen-recorded,
 * and shows blank in Recent apps. Two kinds of screen, each behind its own setting:
 *  - [Kind.KEYS]: pairing, the relay key and its QR code, backups. A screenshot of the pairing QR is
 *    a copy of the long-term key. On by default.
 *  - [Kind.MESSAGES]: chats, the message list and reminders. Off by default.
 *
 * The app installs [policy] (it knows the role and reads the right settings store); the default is
 * the companion's own store, which only has the key-screens setting.
 */
object ScreenGuard {

    enum class Kind { NONE, KEYS, MESSAGES }

    @Volatile
    var policy: (Context, Kind) -> Boolean = { ctx, kind ->
        kind == Kind.KEYS && SenderSettings.get(ctx).protectKeyScreens
    }

    fun isProtected(context: Context, kind: Kind): Boolean =
        kind != Kind.NONE && try { policy(context.applicationContext, kind) } catch (e: Exception) { kind == Kind.KEYS }

    /** Sets or clears FLAG_SECURE on [activity] for [kind]. Call in onCreate (before the first frame,
     *  so Recents never captures it) and again in onResume, to pick up a changed setting. */
    fun apply(activity: Activity, kind: Kind) {
        if (kind == Kind.NONE) return
        if (isProtected(activity, kind)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
