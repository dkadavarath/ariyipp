package com.noti.sender.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.noti.sender.R
import com.noti.sender.util.Theming

/** Help tab content for ariy: how to pair, relay, send on command, and troubleshoot. */
class HelpActivity : AppCompatActivity() {

    private val topics = listOf(
        R.string.help_start_title to R.string.help_start_body,
        R.string.help_pairing_title to R.string.help_pairing_body,
        R.string.help_reverse_title to R.string.help_reverse_body,
        R.string.help_webhook_title to R.string.help_webhook_body,
        R.string.help_sync_title to R.string.help_sync_body,
        R.string.help_trouble_title to R.string.help_trouble_body,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theming.applyPredictiveBackTransitions(this)
        setContentView(R.layout.cmp_activity_help)

        val root = findViewById<View>(R.id.help_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.help_title)
        }

        val container = findViewById<LinearLayout>(R.id.help_container)
        val inflater = LayoutInflater.from(this)
        for ((titleRes, bodyRes) in topics) {
            val card = inflater.inflate(R.layout.cmp_item_help_topic, container, false)
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
