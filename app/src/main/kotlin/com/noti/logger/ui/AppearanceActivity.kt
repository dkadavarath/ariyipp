package com.noti.logger.ui

import android.widget.RadioGroup
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.config.ThemeMode
import com.noti.logger.util.Theming

class AppearanceActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_appearance
    override val titleRes = R.string.title_appearance

    override fun onScreenCreated() {
        val s = Settings.get(this)
        val modeGroup = findViewById<RadioGroup>(R.id.rg_theme_mode)
        val colorGroup = findViewById<RadioGroup>(R.id.rg_theme_color)

        // Set current selection BEFORE attaching listeners (so programmatic checks don't fire them).
        modeGroup.check(
            when (s.themeMode) {
                ThemeMode.SYSTEM -> R.id.rb_mode_system
                ThemeMode.LIGHT -> R.id.rb_mode_light
                ThemeMode.DARK -> R.id.rb_mode_dark
            }
        )
        colorGroup.check(if (s.dynamicColor) R.id.rb_color_material else R.id.rb_color_default)

        modeGroup.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.rb_mode_light -> ThemeMode.LIGHT
                R.id.rb_mode_dark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            if (mode != s.themeMode) {
                s.themeMode = mode
                Theming.applyNightMode(s) // triggers recreate for the new night mode
            }
        }

        colorGroup.setOnCheckedChangeListener { _, id ->
            val dynamic = id == R.id.rb_color_material
            if (dynamic != s.dynamicColor) {
                s.dynamicColor = dynamic
                recreate() // re-run onCreate → applies/removes the dynamic-color overlay
            }
        }
    }
}
