package com.noti.logger.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noti.logger.R
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.reminder.RuleType
import com.noti.logger.reminder.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rulebook: every rule, what it watches, and whether it is on.
 *
 * Long-pressing a rule starts a selection, after which tapping toggles rules rather than opening
 * them - the same gesture the Messages list already uses, so it needs no explaining. Selection is
 * held by rule id rather than by row position, because the list is rebuilt from the database after
 * a delete and positions would point at the wrong rules the moment one disappeared.
 */
class ReminderRulesActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_reminder_rules
    override val titleRes = R.string.title_reminder_rules

    private val selected = LinkedHashSet<Long>()
    private var rules: List<ReminderRuleEntity> = emptyList()

    override fun onScreenCreated() {
        findViewById<View>(R.id.btn_add_rule).setOnClickListener {
            startActivity(Intent(this, ReminderRuleActivity::class.java))
        }
        findViewById<View>(R.id.btn_overflow).setOnClickListener { showOverflowMenu(it) }

        // Back leaves the selection before it leaves the screen - losing a screen because you meant
        // to cancel a selection is a small thing that feels broken.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                clearSelection()
            }
        }.also { backCallback = it })
    }

    private var backCallback: OnBackPressedCallback? = null

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val container = findViewById<LinearLayout>(R.id.rules_container)
        val empty = findViewById<TextView>(R.id.tv_rules_empty)
        lifecycleScope.launch {
            rules = withContext(Dispatchers.IO) {
                try { NotiDatabase.get(applicationContext).reminderRuleDao().all() } catch (e: Exception) { emptyList() }
            }
            // Drop anything that has since been deleted, so the count can never exceed what exists.
            selected.retainAll(rules.map { it.id }.toSet())
            container.removeAllViews()
            empty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
            empty.text = getString(R.string.rm_no_rules)

            val inflater = LayoutInflater.from(this@ReminderRulesActivity)
            for (rule in rules) {
                val row = inflater.inflate(R.layout.item_settings_row, container, false)
                bindRow(row, rule)
                container.addView(row)
            }
            updateSelectionUi()
        }
    }

    private fun bindRow(row: View, rule: ReminderRuleEntity) {
        val isSelected = rule.id in selected
        row.findViewById<TextView>(R.id.row_title).text = rule.name
        bindSub(row.findViewById(R.id.row_sub), rule)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(
            when {
                isSelected -> R.drawable.ic_check
                RuleType.valueOf(rule.type) == RuleType.PARKING -> R.drawable.ic_nav_reminders
                else -> R.drawable.ic_relay
            }
        )
        // A disabled rule stays listed but reads as inert, rather than vanishing and leaving you
        // wondering whether you deleted it: its title steps down to the secondary text colour and
        // its icon tile goes neutral. (Fading the whole row used to drop the text below a readable
        // contrast.)
        val enabled = rule.enabled == 1
        row.findViewById<TextView>(R.id.row_title).setTextColor(
            MaterialColors.getColor(
                row,
                if (enabled) com.google.android.material.R.attr.colorOnSurface
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        )
        row.findViewById<com.google.android.material.card.MaterialCardView>(R.id.icon_container).setCardBackgroundColor(
            MaterialColors.getColor(
                row,
                if (enabled) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHighest,
            )
        )
        row.findViewById<ImageView>(R.id.row_icon).setColorFilter(
            MaterialColors.getColor(
                row,
                if (enabled) com.google.android.material.R.attr.colorOnSecondaryContainer
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        )
        row.setBackgroundColor(
            if (isSelected) {
                MaterialColors.getColor(row, com.google.android.material.R.attr.colorSurfaceVariant)
            } else {
                android.graphics.Color.TRANSPARENT
            }
        )
        row.setOnClickListener {
            if (selected.isEmpty()) {
                startActivity(
                    Intent(this, ReminderRuleActivity::class.java)
                        .putExtra(ReminderRuleActivity.EXTRA_RULE_ID, rule.id)
                )
            } else {
                toggle(rule.id)
            }
        }
        row.setOnLongClickListener {
            toggle(rule.id)
            true
        }
    }

    private fun toggle(id: Long) {
        if (!selected.add(id)) selected.remove(id)
        reload()
    }

    private fun clearSelection() {
        selected.clear()
        reload()
    }

    private fun updateSelectionUi() {
        val any = selected.isNotEmpty()
        backCallback?.isEnabled = any
        findViewById<TextView>(R.id.tv_rules_header).text = when {
            any -> getString(R.string.rm_selected_count, selected.size)
            rules.isNotEmpty() -> getString(R.string.rm_select_hint)
            else -> ""
        }
        findViewById<TextView>(R.id.tv_rules_empty).visibility =
            if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * The same overflow the Messages list uses: plain actions when nothing is selected, the
     * selection actions when something is. Built per-open rather than kept in a menu resource so
     * "Select all" can be left out when everything already is - an item that does nothing is worse
     * than an absent one.
     */
    private fun showOverflowMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        val any = selected.isNotEmpty()
        if (!any) {
            if (rules.isNotEmpty()) popup.menu.add(0, M_SELECT_MODE, 0, R.string.rm_select_items)
            popup.menu.add(0, M_ADD, 1, R.string.rm_add_rule)
        } else {
            if (selected.size < rules.size) popup.menu.add(0, M_SELECT_ALL, 0, R.string.messages_select_all)
            popup.menu.add(0, M_DESELECT_ALL, 1, R.string.messages_deselect_all)
            popup.menu.add(0, M_DELETE, 2, R.string.rm_delete)
            popup.menu.add(0, M_CANCEL, 3, R.string.messages_cancel_selection)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_SELECT_MODE -> rules.firstOrNull()?.let { toggle(it.id) }
                M_ADD -> startActivity(Intent(this, ReminderRuleActivity::class.java))
                M_SELECT_ALL -> { selected.addAll(rules.map { it.id }); reload() }
                M_DESELECT_ALL, M_CANCEL -> clearSelection()
                M_DELETE -> confirmDeleteSelected()
            }
            true
        }
        popup.show()
    }

    private companion object {
        const val M_SELECT_MODE = 1
        const val M_ADD = 2
        const val M_SELECT_ALL = 3
        const val M_DESELECT_ALL = 4
        const val M_DELETE = 5
        const val M_CANCEL = 6
    }

    private fun confirmDeleteSelected() {
        val count = selected.size
        if (count == 0) return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.rm_delete_selected_confirm, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rm_delete_rule) { _, _ -> deleteSelected(count) }
            .show()
    }

    private fun deleteSelected(count: Int) {
        val ids = selected.toList()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = NotiDatabase.get(applicationContext).reminderRuleDao()
                ids.forEach { dao.delete(it) }
            }
            selected.clear()
            Toast.makeText(this@ReminderRulesActivity, getString(R.string.rm_rules_deleted, count), Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    /**
     * "Bill · SMS · ICICIB", ending in "· Auto-paid" (accent colour, so it can be spotted down the
     * list) when the rule marks its bills paid itself. It must not cost a line: on a narrow screen
     * the description is shortened to make room, never the flag - which is the point of it.
     */
    private fun bindSub(sub: TextView, rule: ReminderRuleEntity) {
        val base = describeSource(rule)
        val autoPaid = rule.payEnabled == 1 && rule.payMatchPattern.isNotBlank() &&
            RuleType.valueOf(rule.type) == RuleType.BILL
        sub.maxLines = if (autoPaid) 1 else Int.MAX_VALUE
        if (!autoPaid) { sub.text = base; return }
        val flag = " · " + getString(R.string.rm_rule_auto_paid)
        fun compose(desc: CharSequence) = android.text.SpannableStringBuilder(desc).append(
            flag,
            android.text.style.ForegroundColorSpan(
                MaterialColors.getColor(
                    sub,
                    if (rule.enabled == 1) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            ),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sub.text = compose(base)
        // Width is only known after layout; trim the description to what's left beside the flag.
        sub.post {
            val room = sub.width - sub.paddingLeft - sub.paddingRight - sub.paint.measureText(flag)
            if (room <= 0 || sub.paint.measureText(base) <= room) return@post
            sub.text = compose(android.text.TextUtils.ellipsize(base, sub.paint, room, android.text.TextUtils.TruncateAt.END))
        }
    }

    private fun describeSource(rule: ReminderRuleEntity): String {
        val kind = getString(
            if (RuleType.valueOf(rule.type) == RuleType.PARKING) R.string.rm_rule_type_parking
            else R.string.rm_rule_type_bill
        )
        val source = when (SourceType.valueOf(rule.source)) {
            SourceType.NOTIFICATION -> rule.sourcePackage
            SourceType.SMS -> getString(R.string.rm_rule_source_sms) +
                if (rule.sourceSender.isBlank()) "" else " · ${rule.sourceSender}"
            SourceType.MANUAL -> getString(R.string.rm_rule_source_manual)
        }
        return "$kind · $source"
    }
}
