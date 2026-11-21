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
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.ScaleProvider
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.util.BackToExit
import com.noti.logger.util.Haptics
import com.noti.logger.util.Motion
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
        const val TAB_REMINDERS = "reminders"

        /** Which reminder a notification wants opened, as "bill:<id>" or "parking:<id>". */
        const val EXTRA_REMINDER_FOCUS = "reminder_focus"
    }

    private var role: Role = Role.MAIN

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val settings = Settings.get(this)
        var chosen = settings.role
        // An existing hub (ippu) install updating to the merged app has no role yet but clearly has
        // hub data - default it to MAIN silently instead of showing onboarding.
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
        // Before any tab's fragment exists: Back handlers run newest-first, so a fragment's own
        // (search open, selection in progress) must be registered after this one to win.
        BackToExit.install(this)

        applyWindowInsets()
        // No setSupportActionBar: the CollapsingToolbarLayout draws the title itself.

        // The FAB serves whichever tab is showing: compose on Messages, add-a-one-off on
        // Reminders. One button rather than two, because only one tab is ever visible.
        findViewById<View>(R.id.fab_compose).setOnClickListener {
            if (currentNavId == R.id.nav_reminders) {
                startActivity(Intent(this, ReminderEditActivity::class.java))
            } else {
                startActivity(Intent(this, ComposeActivity::class.java))
            }
        }

        val companion = role == Role.COMPANION
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.inflateMenu(if (companion) R.menu.bottom_nav_menu_companion else R.menu.bottom_nav_menu)
        // The Reminders tab only exists while the module is on - a feature you haven't enabled
        // shouldn't cost you a permanent slot in the nav bar.
        if (!companion && !settings.reminderEnabled) bottomNav.menu.removeItem(R.id.nav_reminders)
        bottomNav.setOnItemSelectedListener { item ->
            if (navHapticsReady) Haptics.tabSelect(bottomNav)
            currentNavId = item.itemId
            when (item.itemId) {
                R.id.nav_status -> show(if (companion) CompanionStatusFragment() else StatusFragment(), R.string.nav_status)
                R.id.nav_messages -> show(MessagesFragment(), R.string.nav_messages)
                R.id.nav_reminders -> show(RemindersFragment(), R.string.nav_reminders)
                R.id.nav_settings -> show(if (companion) CompanionSettingsFragment() else SettingsFragment(), R.string.nav_settings)
                R.id.nav_about -> show(AboutFragment(), R.string.nav_about)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = when {
                companion -> R.id.nav_status
                intent.getStringExtra(EXTRA_START_TAB) == TAB_MESSAGES -> R.id.nav_messages
                intent.getStringExtra(EXTRA_START_TAB) == TAB_REMINDERS &&
                    bottomNav.menu.findItem(R.id.nav_reminders) != null -> R.id.nav_reminders
                else -> R.id.nav_status
            }
        }
        navHapticsReady = true // don't buzz on the initial programmatic selection above

        // A reminder notification names the thing it is about, so tapping it lands on that entry's
        // editor rather than on a list the user then has to search. This is what makes the "needs
        // your input" notification actually useful: it opens the form with the missing field.
        openReminderFocus(intent)

        requestNotificationPermissionIfNeeded()
    }

    /** The AMOLED state this activity was themed with, so we can re-theme if it changed in Settings. */
    private var themedAmoled = false

    /** Gates tab-select haptics so the launch-time programmatic selection doesn't buzz. */
    private var navHapticsReady = false

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
        // A real app-level resume (unlike an in-app tab switch, which never pauses this Activity) -
        // permissions may have changed if the user was away in system Settings, so the Status tab
        // should actually re-check them next time it resumes instead of trusting its cached state.
        StatusCache.permissionsNeedRefresh = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReminderFocus(intent)
    }

    /** Opens the entry a reminder notification refers to, as "bill:<id>" or "parking:<id>". */
    private fun openReminderFocus(intent: Intent) {
        val focus = intent.getStringExtra(EXTRA_REMINDER_FOCUS) ?: return
        // Consume it, so a rotation or a return to this activity doesn't reopen the editor.
        intent.removeExtra(EXTRA_REMINDER_FOCUS)
        val (kind, rawId) = focus.split(":", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else return
        }
        val id = rawId.toLongOrNull() ?: return
        if (id <= 0) return
        val edit = Intent(this, ReminderEditActivity::class.java)
        when (kind) {
            "bill" -> edit.putExtra(ReminderEditActivity.EXTRA_BILL_ID, id)
            "parking" -> edit.putExtra(ReminderEditActivity.EXTRA_PARKING_ID, id)
            else -> return
        }
        startActivity(edit)
    }

    /** Shows the total unread count as a badge on the Messages tab. MAIN only. */
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
                    backgroundColor = MaterialColors.getColor(bottomNav, androidx.appcompat.R.attr.colorPrimary)
                }
            } else {
                bottomNav.removeBadge(R.id.nav_messages)
            }
        }
    }

    private fun show(fragment: Fragment, titleRes: Int) {
        val fab = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_compose)
        // The FAB shows on the two tabs that can create something.
        val wantsFab = fragment is MessagesFragment || fragment is RemindersFragment
        if (navHapticsReady) {
            // A user-initiated tab change: fade through. Tabs are siblings, not a sequence, so the
            // content swaps in place rather than sliding; kept short because it runs on every tap.
            supportFragmentManager.findFragmentById(R.id.nav_container)?.exitTransition = fadeThrough()
            fragment.enterTransition = fadeThrough()
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.nav_container, fragment)
                .commit()
            Motion.setShown(fab, wantsFab, fromScale = 0.9f)
        } else {
            // Initial (programmatic) selection at launch: no animation.
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_container, fragment)
                .commitNow()
            Motion.setShownNow(fab, wantsFab)
        }
        // The large collapsing header owns the title; start each tab fully expanded.
        findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title = getString(titleRes)
        findViewById<AppBarLayout>(R.id.appbar).setExpanded(true, false)
        fab.contentDescription = getString(
            if (fragment is RemindersFragment) R.string.rm_add_manual else R.string.nav_messages
        )
    }

    /** The outgoing tab fades out quickly, then the incoming one fades up from 96%. */
    private fun fadeThrough() = MaterialFadeThrough().apply {
        duration = 240
        interpolator = Motion.EASE_OUT
        (secondaryAnimatorProvider as? ScaleProvider)?.incomingStartScale = 0.96f
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
