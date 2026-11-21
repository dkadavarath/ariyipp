package com.noti.logger.util

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.noti.logger.R

/**
 * Back on a root screen never leaves the app on its own: the first press only says "press back
 * again to exit", and a second press within [WINDOW_MS] leaves - to the background, keeping the
 * open tab, so the app comes back where it was. A stray back swipe is easy to make and closing the
 * app costs the user their place.
 *
 * Anything a screen registers (search open, a selection in progress) is handled first - Back
 * clears that before this ever sees it. Back handlers run newest-first, so this one is added once,
 * deliberately NOT tied to a lifecycle: a lifecycle-bound callback is removed on every stop and
 * re-added on every start, and the activity's own start fires after its fragments' - so coming
 * back from another screen would make this the newest handler and it would swallow the Back that
 * should have cleared a selection.
 */
object BackToExit {

    private const val WINDOW_MS = 2_000L

    fun install(activity: ComponentActivity) {
        var armedAt = 0L
        var toast: Toast? = null
        activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - armedAt <= WINDOW_MS) {
                    toast?.cancel()
                    armedAt = 0L
                    // Leave by sending the app to the back, not by finishing it: a finished activity
                    // comes back from the launcher as a fresh start on the first tab, losing your
                    // place. Android 12's "the root survives Back" only applies to the system's own
                    // Back handling - re-dispatching it from here (as this used to) finishes the
                    // activity. Device-verified: wm_finish_activity ... app-request.
                    if (activity.isTaskRoot) {
                        activity.moveTaskToBack(true)
                    } else {
                        isEnabled = false
                        activity.onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                } else {
                    armedAt = now
                    toast = Toast.makeText(activity, R.string.back_again_to_exit, Toast.LENGTH_SHORT).also { it.show() }
                }
            }
        })
    }
}
