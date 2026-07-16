package com.noti.logger.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.noti.logger.R
import com.noti.logger.config.AppRules
import com.noti.logger.config.Settings
import com.noti.logger.redact.AppRule
import com.noti.logger.util.AppLabelCache
import com.noti.logger.util.InstalledApps
import kotlinx.coroutines.launch

/**
 * Per-app capture rules: a keyword include-filter and a note to append, for each app in the
 * included-apps selection. Rows are inflated into a ScrollView (not a RecyclerView) so the
 * editors keep their text — recycling live EditTexts would shuffle input between apps.
 */
class AppRulesActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_app_rules
    override val titleRes = R.string.title_app_rules

    private class Row(val packageName: String, val keywords: EditText, val notes: EditText)

    private val rows = ArrayList<Row>()

    override fun onScreenCreated() {
        val settings = Settings.get(this)
        val included = settings.includedPackages
        val saveButton = findViewById<View>(R.id.btn_save)

        // No allowlist ⇒ every app is captured and there is nothing to list here.
        if (included.isEmpty()) {
            findViewById<TextView>(R.id.txt_empty).visibility = View.VISIBLE
            findViewById<TextView>(R.id.txt_intro).visibility = View.GONE
            saveButton.visibility = View.GONE
            return
        }

        saveButton.setOnClickListener { save(settings) }
        buildRows(settings, included)
    }

    private fun buildRows(settings: Settings, included: Set<String>) {
        val container = findViewById<LinearLayout>(R.id.rules_container)
        val inflater = LayoutInflater.from(this)

        lifecycleScope.launch {
            val installed = InstalledApps.loadLaunchableApps(this@AppRulesActivity)
                .associateBy { it.packageName }
            val labels = AppLabelCache(applicationContext)
            val rules = settings.appRules

            val ordered = included.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { installed[it]?.label ?: labels.label(it) }
            )

            for (pkg in ordered) {
                val view = inflater.inflate(R.layout.item_app_rule, container, false)
                val app = installed[pkg]
                view.findViewById<TextView>(R.id.txt_label).text = app?.label ?: labels.label(pkg)
                view.findViewById<TextView>(R.id.txt_package).text = pkg
                view.findViewById<ImageView>(R.id.img_icon).setImageDrawable(app?.icon)

                val keywords = view.findViewById<EditText>(R.id.et_keywords)
                val notes = view.findViewById<EditText>(R.id.et_notes)
                rules[pkg]?.let {
                    keywords.setText(AppRules.formatKeywords(it.keywords))
                    notes.setText(it.notes)
                }

                rows.add(Row(pkg, keywords, notes))
                container.addView(view)
            }
        }
    }

    private fun save(settings: Settings) {
        // Merge rather than replace: rules for apps not currently selected stay untouched.
        val merged = settings.appRules.toMutableMap()
        for (row in rows) {
            val rule = AppRule(
                keywords = AppRules.parseKeywords(row.keywords.text.toString()),
                notes = row.notes.text.toString().trim()
            )
            if (rule.isEmpty) merged.remove(row.packageName) else merged[row.packageName] = rule
        }
        settings.appRules = merged
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
