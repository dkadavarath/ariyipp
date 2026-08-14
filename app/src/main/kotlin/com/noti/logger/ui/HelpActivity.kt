package com.noti.logger.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.noti.logger.R

class HelpActivity : AppCompatActivity() {

    // (title, body) help topics, rendered as cards.
    private val topics = listOf(
        R.string.help_start_title to R.string.help_start_body,
        R.string.help_webhook_title to R.string.help_webhook_body,
        R.string.help_apps_title to R.string.help_apps_body,
        R.string.help_rules_title to R.string.help_rules_body,
        R.string.help_dupes_title to R.string.help_dupes_body,
        R.string.help_triggers_title to R.string.help_triggers_body,
        R.string.help_privacy_title to R.string.help_privacy_body,
        R.string.help_trouble_title to R.string.help_trouble_body,
        R.string.help_playprotect_title to R.string.help_playprotect_body
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        val root = findViewById<View>(R.id.help_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val container = findViewById<LinearLayout>(R.id.help_container)
        val inflater = LayoutInflater.from(this)
        for ((titleRes, bodyRes) in topics) {
            val card = inflater.inflate(R.layout.item_help_topic, container, false)
            card.findViewById<TextView>(R.id.tv_topic_title).setText(titleRes)
            card.findViewById<TextView>(R.id.tv_topic_body).setText(bodyRes)
            container.addView(card)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
