package com.noti.sender.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.noti.sender.R
import com.noti.sender.util.Theming

/** Bottom-nav host: Status / Settings / About tabs. */
class MainActivity : AppCompatActivity() {

    private var themedAmoled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        super.onCreate(savedInstanceState)
        Theming.applyAmoledIfEnabled(this) // after super so AppCompat doesn't reset the overlay
        themedAmoled = Theming.amoledActive(this)
        setContentView(R.layout.activity_main)

        applyWindowInsets()
        setSupportActionBar(findViewById(R.id.toolbar))

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> show(StatusFragment(), R.string.nav_status)
                R.id.nav_settings -> show(SettingsFragment(), R.string.nav_settings)
                R.id.nav_about -> show(AboutFragment(), R.string.nav_about)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        if (savedInstanceState == null) bottomNav.selectedItemId = R.id.nav_status
    }

    override fun onResume() {
        super.onResume()
        // The Appearance toggle lives in a sub-screen; if AMOLED changed while away, re-theme.
        if (Theming.amoledActive(this) != themedAmoled) recreate()
    }

    private fun show(fragment: Fragment, titleRes: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_container, fragment)
            .commitNow()
        supportActionBar?.setTitle(titleRes)
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.main_root)
        val appbar = findViewById<AppBarLayout>(R.id.appbar)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appbar.setPadding(0, bars.top, 0, 0)
            bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }
}
