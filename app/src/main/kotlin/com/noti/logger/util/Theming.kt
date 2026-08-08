package com.noti.logger.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.noti.logger.R
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

    /** True when the AMOLED overlay would currently apply (setting on AND dark mode active). */
    fun amoledActive(activity: Activity): Boolean {
        if (!Settings.get(activity).amoled) return false
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
}
