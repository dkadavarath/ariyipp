package com.noti.logger.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.noti.logger.R
import com.noti.logger.config.Settings

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val root = findViewById<View>(R.id.about_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        // Standard platform up-navigation (default back arrow + system behaviour).
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.tv_version).text = getString(R.string.about_version, version)

        findViewById<TextView>(R.id.tv_device_id).text =
            getString(R.string.about_device_id, Settings.get(this).deviceId)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
