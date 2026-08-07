package com.noti.sender.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Minimal launcher so the app leaves the "stopped" state and its SMS receiver starts getting
 * broadcasts. The real configuration UI (service-account key import, pairing, toggles) replaces
 * this in a later step.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = "noti sender\n\nForwards incoming SMS to noti via encrypted FCM.\n" +
                "Configuration coming soon."
            textSize = 16f
            setPadding(56, 120, 56, 56)
        }
        setContentView(view)
    }
}
