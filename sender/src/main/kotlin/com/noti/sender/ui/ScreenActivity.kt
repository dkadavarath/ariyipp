package com.noti.sender.ui

import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.noti.sender.R

/**
 * Base for sndi's config sub-screens — standard up-navigation and edge-to-edge insets, matching
 * noti's ScreenActivity. Subclasses provide a layout with `@id/screen_root` and `@id/toolbar`.
 */
abstract class ScreenActivity : AppCompatActivity() {

    @get:LayoutRes protected abstract val layoutRes: Int
    @get:StringRes protected abstract val titleRes: Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutRes)

        val root = findViewById<View>(R.id.screen_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(titleRes)
        }

        onScreenCreated()
    }

    protected open fun onScreenCreated() {}

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
