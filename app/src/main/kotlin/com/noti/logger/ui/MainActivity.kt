package com.noti.logger.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.MaterialSharedAxis
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.util.Haptics
import com.noti.logger.util.Theming
import com.noti.shared.Role
import com.noti.sender.ui.StatusFragment as CompanionStatusFragment
import com.noti.sender.ui.SettingsFragment as CompanionSettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom-nav host. Tabs depend on the role: MAIN = Status/Messages/Settings/About, COMPANION =
 *  Status/Settings/About (the companion Status/Settings fragments come from the companion library). */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_TAB = "start_tab"
        const val TAB_MESSAGES = "messages"
    }

    private var role: Role = Role.MAIN

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val settings = Settings.get(this)
        var chosen = settings.role
        // An existing hub (ippu) install updating to the merged app has no role yet but clearly has
        // hub data — default it to MAIN silently instead of showing onboarding.
        if (chosen == null && (settings.webhookUrl.isNotBlank() || settings.pushInboundEnabled || settings.relayKey.isNotBlank())) {
            chosen = Role.MAIN
            settings.role = Role.MAIN
        }
        if (chosen == null) {
            startActivity(Intent(this, RoleOnboardingActivity::class.java))
            finish()
            return
        }
        role = chosen

        Theming.applyAmoledIfEnabled(this) // after super so AppCompat doesn't reset the overlay
        themedAmoled = Theming.amoledActive(this)
        setContentView(R.layout.activity_main)

        applyWindowInsets()
        // No setSupportActionBar: the CollapsingToolbarLayout draws the title itself.

        findViewById<View>(R.id.fab_compose).setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java))
        }

        val companion = role == Role.COMPANION
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.inflateMenu(if (companion) R.menu.bottom_nav_menu_companion else R.menu.bottom_nav_menu)
        bottomNav.setOnItemSelectedListener { item ->
            if (navHapticsReady) Haptics.tabSelect(bottomNav)
            val forward = NAV_ORDER.indexOf(item.itemId) >= NAV_ORDER.indexOf(currentNavId)
            currentNavId = item.itemId
            when (item.itemId) {
                R.id.nav_status -> show(if (companion) CompanionStatusFragment() else StatusFragment(), R.string.nav_status, forward)
                R.id.nav_messages -> show(MessagesFragment(), R.string.nav_messages, forward)
                R.id.nav_settings -> show(if (companion) CompanionSettingsFragment() else SettingsFragment(), R.string.nav_settings, forward)
                R.id.nav_about -> show(AboutFragment(), R.string.nav_about, forward)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        if (savedInstanceState == null) {
            bottomNav.selectedItemId =
                if (!companion && intent.getStringExtra(EXTRA_START_TAB) == TAB_MESSAGES) R.id.nav_messages
                else R.id.nav_status
        }
        navHapticsReady = true // don't buzz on the initial programmatic selection above

        requestNotificationPermissionIfNeeded()
    }

    /** The AMOLED state this activity was themed with, so we can re-theme if it changed in Settings. */
    private var themedAmoled = false

    /** Gates tab-select haptics so the launch-time programmatic selection doesn't buzz. */
    private var navHapticsReady = false

    /** Left-to-right tab order, so a tab change knows which way to slide (shared-axis X). */
    private val NAV_ORDER = listOf(R.id.nav_status, R.id.nav_messages, R.id.nav_settings, R.id.nav_about)
    private var currentNavId = R.id.nav_status

    override fun onResume() {
        super.onResume()
        if (isFinishing) return // routed to onboarding; UI not built
        // The Appearance toggle lives in a sub-screen; if it changed while we were away, re-theme.
        if (Theming.amoledActive(this) != themedAmoled) {
            recreate()
            return
        }
        updateMessagesBadge()
    }

    /** Shows the total unread count as a badge on the Messages tab (Signal-style). MAIN only. */
    fun updateMessagesBadge() {
        if (role != Role.MAIN) return
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        lifecycleScope.launch {
            val unread = withContext(Dispatchers.IO) {
                NotiDatabase.get(this@MainActivity).relayedMessageDao().totalUnread()
            }
            if (unread > 0) {
                bottomNav.getOrCreateBadge(R.id.nav_messages).apply {
                    isVisible = true
                    number = unread
                    backgroundColor = MaterialColors.getColor(bottomNav, com.google.android.material.R.attr.colorPrimary)
                }
            } else {
                bottomNav.removeBadge(R.id.nav_messages)
            }
        }
    }

    private fun show(fragment: Fragment, titleRes: Int, forward: Boolean = true) {
        if (navHapticsReady) {
            // A user-initiated tab change: slide the outgoing and incoming content along X.
            supportFragmentManager.findFragmentById(R.id.nav_container)?.exitTransition =
                MaterialSharedAxis(MaterialSharedAxis.X, forward)
            fragment.enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, forward)
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.nav_container, fragment)
                .commit()
        } else {
            // Initial (programmatic) selection at launch: no animation.
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_container, fragment)
                .commitNow()
        }
        // The large collapsing header owns the title; start each tab fully expanded.
        findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title = getString(titleRes)
        findViewById<AppBarLayout>(R.id.appbar).setExpanded(true, false)
        // The compose FAB is only for the Messages tab.
        findViewById<View>(R.id.fab_compose).visibility =
            if (fragment is MessagesFragment) View.VISIBLE else View.GONE
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

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
