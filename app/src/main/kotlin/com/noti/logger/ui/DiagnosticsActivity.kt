package com.noti.logger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.noti.logger.R
import com.noti.shared.Diag

/** Shows the in-app diagnostics log so relay/compose failures are visible (with their reasons). */
class DiagnosticsActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_diagnostics
    override val titleRes = R.string.title_diagnostics

    private lateinit var logView: TextView

    override fun onScreenCreated() {
        logView = findViewById(R.id.txt_diag)
        logView.movementMethod = ScrollingMovementMethod()
        findViewById<View>(R.id.btn_diag_clear).setOnClickListener { Diag.clear() }
        findViewById<View>(R.id.btn_diag_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("diagnostics", Diag.snapshot().joinToString("\n")))
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Diag.listener = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        super.onPause()
        Diag.listener = null
    }

    private fun render() {
        val lines = Diag.snapshot()
        logView.text = if (lines.isEmpty()) getString(R.string.diag_empty) else lines.joinToString("\n")
    }
}
