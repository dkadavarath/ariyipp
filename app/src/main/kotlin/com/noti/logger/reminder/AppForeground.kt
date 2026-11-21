package com.noti.logger.reminder

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any of this app's activities is currently on screen.
 *
 * Exists for one reason, established by testing on a real device: `AlarmClock.ACTION_SET_ALARM` is
 * an *activity start*, and Android 10+ blocks activity starts from the background - silently. The
 * platform does not throw, does not return an error, and `startActivity` completes normally; the
 * only trace is a `Background activity launch blocked ... (BAL_BLOCK)` line in logcat that the app
 * cannot see. So there is no way to detect the failure after the fact, and the only honest option is
 * to know beforehand whether the call can possibly work.
 *
 * A counter over activity starts/stops rather than ActivityManager's running-process importance:
 * it is exact, needs no permission, and costs nothing at the point it is read.
 */
object AppForeground {

    @Volatile private var started = 0

    fun isForeground(): Boolean = started > 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { if (started > 0) started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
