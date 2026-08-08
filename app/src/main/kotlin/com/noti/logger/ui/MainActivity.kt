package com.noti.logger.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.noti.logger.R
import com.noti.logger.data.NotiDatabase
import com.noti.logger.util.Theming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom-nav host: Status / Settings / About tabs. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyWindowInsets()
        setSupportActionBar(findViewById(R.id.toolbar))

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> show(StatusFragment(), R.string.nav_status)
                R.id.nav_messages -> show(MessagesFragment(), R.string.nav_messages)
                R.id.nav_settings -> show(SettingsFragment(), R.string.nav_settings)
                R.id.nav_about -> show(AboutFragment(), R.string.nav_about)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_status
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateMessagesBadge()
    }

    /** Shows the total unread count as a badge on the Messages tab (Signal-style). */
    private fun updateMessagesBadge() {
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

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
