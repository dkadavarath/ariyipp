package com.noti.logger.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.config.ThemeMode
import com.noti.sender.config.SenderSettings
import com.noti.shared.Role

/**
 * Central application of the user's theme choices (mode + Default/Material color + AMOLED). The
 * companion role stores its appearance in [SenderSettings] (its Appearance screen comes from the
 * companion library), so in that role we read the effective theme from there - keeping the app shell
 * in sync with the companion's own screens.
 */
object Theming {

    private fun isCompanion(context: Context) = Settings.get(context).role == Role.COMPANION

    private fun themeMode(context: Context): ThemeMode {
        val name = if (isCompanion(context)) SenderSettings.get(context).themeMode.name
        else Settings.get(context).themeMode.name
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private fun dynamicColor(context: Context): Boolean =
        if (isCompanion(context)) SenderSettings.get(context).dynamicColor else Settings.get(context).dynamicColor

    private fun amoled(context: Context): Boolean =
        if (isCompanion(context)) SenderSettings.get(context).amoled else Settings.get(context).amoled

    /** Apply the light/dark/system mode globally. Call from Application.onCreate and on change. */
    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode(context)) {
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * If the user chose "Material", overlay Material You dynamic color on this activity. Must be
     * called at the very top of onCreate (before super.onCreate/setContentView). No-op for the
     * Default (blue) theme or on devices without dynamic color.
     */
    fun applyDynamicColorIfEnabled(activity: Activity) {
        if (dynamicColor(activity)) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }

    /** True when the AMOLED overlay would currently apply (setting on AND dark mode active). */
    fun amoledActive(activity: Activity): Boolean {
        if (!amoled(activity)) return false
        val mask = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Push chrome/background surfaces to true black when AMOLED is on and dark mode is active. Call
     * after super.onCreate (theme resolved) and before setContentView. No-op in light mode.
     */
    fun applyAmoledIfEnabled(activity: Activity) {
        if (!amoledActive(activity)) return
        activity.theme.applyStyle(R.style.ThemeOverlay_Noti_Amoled, true)
        // Force the window background too: it's resolved from the theme at window creation, so the
        // overlay alone won't repaint it.
        activity.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
    }

    /**
     * On API 34+, replaces Theme.Noti's static open/close window animations with the
     * predictive-back-aware equivalent for this activity, so navigating to/from it shows a live
     * interactive preview during the back gesture instead of a canned slide playing only on release.
     * Older API levels keep using the theme's windowAnimationStyle unchanged - this only ever adds
     * behavior on top of it, never removes the fallback. Skip calling this on an activity whose close
     * transition should exit straight to the launcher (e.g. the root/onboarding activity), so the
     * system's own "peek home screen" predictive animation shows instead of a custom one.
     */
    fun applyPredictiveBackTransitions(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.screen_open_enter, R.anim.screen_open_exit)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.screen_close_enter, R.anim.screen_close_exit)
        }
    }
}
