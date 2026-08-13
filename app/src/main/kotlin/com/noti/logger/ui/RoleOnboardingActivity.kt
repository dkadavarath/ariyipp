package com.noti.logger.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.util.Theming
import com.noti.shared.Role

/** First-run role picker. Sets the role, then hands off to [MainActivity], which builds the right UI. */
class RoleOnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        findViewById<View>(R.id.card_main).setOnClickListener { choose(Role.MAIN) }
        findViewById<View>(R.id.card_companion).setOnClickListener { choose(Role.COMPANION) }
    }

    private fun choose(role: Role) {
        Settings.get(this).role = role
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
