package com.noti.logger.util

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.noti.logger.config.Settings
import com.noti.logger.config.ThemeMode

/** Central application of the user's theme choices (mode + Default/Material color). */
object Theming {

    /** Apply the light/dark/system mode globally. Call from Application.onCreate and on change. */
    fun applyNightMode(settings: Settings) {
        AppCompatDelegate.setDefaultNightMode(
            when (settings.themeMode) {
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
        if (Settings.get(activity).dynamicColor) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }
}
