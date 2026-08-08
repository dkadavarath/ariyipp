package com.noti.sender.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.noti.sender.R
import com.noti.sender.config.SenderSettings
import com.noti.sender.config.ThemeMode

/** Central application of the user's theme choices (mode + Default/Material color + AMOLED). */
object Theming {

    /** Apply the light/dark/system mode globally. Call from Application.onCreate and on change. */
    fun applyNightMode(settings: SenderSettings) {
        AppCompatDelegate.setDefaultNightMode(
            when (settings.themeMode) {
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Overlay Material You dynamic color if the user chose it. Call before super.onCreate. */
    fun applyDynamicColorIfEnabled(activity: Activity) {
        if (SenderSettings.get(activity).dynamicColor) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }

    /** True when the AMOLED overlay would currently apply (setting on AND dark mode active). */
    fun amoledActive(activity: Activity): Boolean {
        if (!SenderSettings.get(activity).amoled) return false
        val mask = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Push chrome/background/card surfaces to true black when AMOLED is on and dark mode is active.
     * Call AFTER super.onCreate (so AppCompat doesn't reset it) and before setContentView; the window
     * background is resolved earlier, so force it too.
     */
    fun applyAmoledIfEnabled(activity: Activity) {
        if (!amoledActive(activity)) return
        activity.theme.applyStyle(R.style.ThemeOverlay_Sendi_Amoled, true)
        activity.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
    }
}
