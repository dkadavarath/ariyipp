package com.noti.sender.ui

import android.widget.RadioGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.sender.R
import com.noti.sender.config.SenderSettings
import com.noti.sender.config.ThemeMode
import com.noti.sender.util.Theming

/** Theme mode (system/light/dark), Default vs Material You colour, and an AMOLED toggle. */
class AppearanceActivity : ScreenActivity() {

    override val layoutRes = R.layout.cmp_activity_appearance
    override val titleRes = R.string.title_appearance

    override fun onScreenCreated() {
        val s = SenderSettings.get(this)
        val modeGroup = findViewById<RadioGroup>(R.id.rg_theme_mode)
        val colorGroup = findViewById<RadioGroup>(R.id.rg_theme_color)
        val amoled = findViewById<MaterialSwitch>(R.id.sw_amoled)

        // Set current selection BEFORE attaching listeners so programmatic checks don't fire them.
        modeGroup.check(
            when (s.themeMode) {
                ThemeMode.SYSTEM -> R.id.rb_mode_system
                ThemeMode.LIGHT -> R.id.rb_mode_light
                ThemeMode.DARK -> R.id.rb_mode_dark
            }
        )
        colorGroup.check(if (s.dynamicColor) R.id.rb_color_material else R.id.rb_color_default)
        amoled.isChecked = s.amoled

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
                recreate()
            }
        }

        amoled.setOnCheckedChangeListener { _, checked ->
            if (checked != s.amoled) {
                s.amoled = checked
                recreate()
            }
        }
    }
}
