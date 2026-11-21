package com.noti.logger.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.reminder.DueMode
import com.noti.logger.reminder.Recurrence
import com.noti.logger.reminder.PatternBuilder
import com.noti.logger.reminder.ReminderExtractor
import com.noti.logger.reminder.RuleType
import com.noti.logger.reminder.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rule editor. Half of it is regex fields, so the important part is the live test at the bottom:
 * paste a real statement, press Test, and see exactly what each pattern pulled out. Writing a regex
 * blind on a phone keyboard and waiting a day to find out it was wrong is the thing this exists to
 * prevent - which is also why an invalid pattern is reported inline rather than at scan time.
 *
 * Fields show and hide by rule type rather than being greyed: a parking rule has no use for an
 * amount pattern, and showing it only invites filling it in.
 */
class ReminderRuleActivity : ScreenActivity() {

    /** Set once a save has started; see save(). */
    private var saving = false

    companion object {
        private const val STATE_PICKED = "picked_package"
        private const val STATE_PAY_PICKED = "pay_package"
        private const val STATE_PICKING_PAY = "picking_for_payment"
        const val EXTRA_RULE_ID = "rule_id"
        /** A new rule drafted from a message: its text becomes the sample, its sender the filter. */
        const val EXTRA_SAMPLE = "sample"
        const val EXTRA_SENDER = "sender"
        /** An existing rule ([EXTRA_RULE_ID]) given its payment side from a confirmation message. */
        const val EXTRA_PAY_SAMPLE = "pay_sample"
        const val EXTRA_PAY_SENDER = "pay_sender"
        private const val SEL_MENU_BASE = 0x5e1
    }

    override val layoutRes = R.layout.activity_reminder_rule
    override val titleRes = R.string.title_reminder_rule
    override val screenKind = com.noti.sender.ui.ScreenGuard.Kind.MESSAGES

    private var ruleId: Long = 0
    private var existing: ReminderRuleEntity? = null
    private var pickedPackage: String = ""
    private var payPackage: String = ""
    /** Which source the shared app picker is choosing for. */
    private var pickingForPayment = false

    private val appPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        // The picker is multi-select; one rule watches one app, so the first choice wins.
        result.data?.getStringArrayExtra(AppPickerActivity.EXTRA_SELECTED)?.firstOrNull()?.let {
            if (pickingForPayment) {
                payPackage = it
                updatePayUi()
                return@registerForActivityResult
            }
            pickedPackage = it
            updateSourceUi()
        }
    }

    override fun onScreenCreated() {
        ruleId = intent.getLongExtra(EXTRA_RULE_ID, 0L)

        findViewById<RadioGroup>(R.id.rg_type).setOnCheckedChangeListener { _, _ -> updateTypeUi() }
        findViewById<RadioGroup>(R.id.rg_source).setOnCheckedChangeListener { _, _ -> updateSourceUi() }
        findViewById<RadioGroup>(R.id.rg_due_mode).setOnCheckedChangeListener { _, _ -> updateDueUi() }

        findViewById<View>(R.id.btn_pick_app).setOnClickListener { pickingForPayment = false; pickApp() }
        findViewById<View>(R.id.btn_test_rule).setOnClickListener { runTest() }
        findViewById<View>(R.id.btn_from_selection).setOnClickListener { buildFromSelection(it) }
        findViewById<View>(R.id.btn_auto_fill).setOnClickListener { autoFill() }
        setUpSampleField(R.id.et_sample, R.id.til_sample) { selectionFields() }
        setUpPaymentSection()
        findViewById<View>(R.id.btn_toggle_help).setOnClickListener { toggleHelp() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        findViewById<View>(R.id.btn_delete).setOnClickListener { confirmDelete() }

        val restored = savedState
        if (restored != null) {
            // Rotated (or recreated): every field restored itself; put back what lives outside them.
            pickedPackage = restored.getString(STATE_PICKED, "")
            payPackage = restored.getString(STATE_PAY_PICKED, "")
            pickingForPayment = restored.getBoolean(STATE_PICKING_PAY, false)
            if (ruleId > 0) load(fillFields = false)
            updateTypeUi(); updateSourceUi(); updatePayUi()
        } else if (ruleId > 0) {
            load()
        } else {
            applyDefaults()
            intent.getStringExtra(EXTRA_SAMPLE)?.takeIf { it.isNotBlank() }?.let { sample ->
                prefillFromMessage(sample, intent.getStringExtra(EXTRA_SENDER).orEmpty())
            }
        }
    }

    // ---- Drafting from a message ----

    /** Long-press → "Create reminder rule" in a chat lands here: an SMS rule for that sender, the
     *  message as the test sample, and every field the parser could read already drafted. */
    private fun prefillFromMessage(sample: String, sender: String) {
        findViewById<RadioButton>(R.id.rb_source_sms).isChecked = true
        val filter = com.noti.logger.reminder.RuleAutoParser.senderFilter(sender)
        findViewById<EditText>(R.id.et_sender).setText(filter)
        findViewById<EditText>(R.id.et_name).setText(filter)
        findViewById<EditText>(R.id.et_sample).setText(sample)
        updateTypeUi()
        autoFill()
    }

    /**
     * Drafts patterns from the sample into the fields that are still empty - a pattern the user wrote
     * or corrected is never overwritten, so this is safe to tap again after editing. Bills only:
     * parking messages vary too much between operators to guess at usefully.
     */
    private fun autoFill() {
        val sample = findViewById<EditText>(R.id.et_sample).text.toString()
        if (sample.isBlank() || isParking()) {
            Toast.makeText(this, R.string.rm_auto_nothing, Toast.LENGTH_LONG).show()
            return
        }
        val d = com.noti.logger.reminder.RuleAutoParser.draft(sample)
        var filled = 0
        fun put(id: Int, value: String?) {
            if (value != null && text(id).isBlank()) { findViewById<EditText>(id).setText(value); filled++ }
        }
        put(R.id.et_match, d.matchPattern)
        put(R.id.et_amount, d.amountPattern)
        put(R.id.et_currency, d.currencyPattern)
        val dateWasEmpty = text(R.id.et_date).isBlank()
        put(R.id.et_date, d.datePattern)
        // The format belongs to the date pattern just drafted; never pair it with a hand-written one.
        if (dateWasEmpty && d.datePattern != null && d.dateFormat != null && text(R.id.et_format).isBlank()) {
            findViewById<EditText>(R.id.et_format).setText(d.dateFormat)
        }
        Toast.makeText(
            this,
            if (filled > 0) getString(R.string.rm_auto_filled, filled) else getString(R.string.rm_auto_nothing),
            Toast.LENGTH_LONG,
        ).show()
        runTest()
    }

    // ---- Payment resolver ----

    private fun payEnabled() = findViewById<MaterialSwitch>(R.id.sw_pay_enabled).isChecked && !isParking()

    private fun setUpPaymentSection() {
        findViewById<MaterialSwitch>(R.id.sw_pay_enabled).setOnCheckedChangeListener { _, _ -> updatePayUi() }
        findViewById<MaterialSwitch>(R.id.sw_pay_match_amount).setOnCheckedChangeListener { _, _ -> updatePayUi(); runPayTest() }
        findViewById<RadioGroup>(R.id.rg_pay_source).setOnCheckedChangeListener { _, _ -> updatePayUi() }
        findViewById<View>(R.id.btn_pay_pick_app).setOnClickListener { pickingForPayment = true; pickApp() }
        findViewById<View>(R.id.btn_pay_auto_fill).setOnClickListener { autoFillPayment() }
        findViewById<View>(R.id.btn_pay_test).setOnClickListener { runPayTest() }
        setUpSampleField(R.id.et_pay_sample, R.id.til_pay_sample) {
            listOf(
                Triple(R.string.rm_field_identify, PatternBuilder.Field.IDENTIFY, R.id.et_pay_match),
                Triple(R.string.rm_pattern_amount, PatternBuilder.Field.AMOUNT, R.id.et_pay_amount),
            )
        }
        updatePayUi()
    }

    /** Parking has nothing to pay, so the whole section goes with it. */
    private fun updatePayUi() {
        findViewById<View>(R.id.card_pay).visibility = if (isParking()) View.GONE else View.VISIBLE
        val on = findViewById<MaterialSwitch>(R.id.sw_pay_enabled).isChecked
        findViewById<View>(R.id.pay_container).visibility = if (on) View.VISIBLE else View.GONE
        val fromApp = findViewById<RadioButton>(R.id.rb_pay_app).isChecked
        findViewById<View>(R.id.til_pay_sender).visibility = if (fromApp) View.GONE else View.VISIBLE
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pay_pick_app).apply {
            visibility = if (fromApp) View.VISIBLE else View.GONE
            text = if (payPackage.isBlank()) getString(R.string.rm_rule_source_pick) else appLabel(payPackage)
        }
        findViewById<TextView>(R.id.tv_pay_match_hint).setText(
            if (findViewById<MaterialSwitch>(R.id.sw_pay_match_amount).isChecked) R.string.rm_pay_match_on
            else R.string.rm_pay_match_off
        )
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun prefillPayment(sample: String, sender: String) {
        findViewById<MaterialSwitch>(R.id.sw_pay_enabled).isChecked = true
        findViewById<RadioButton>(R.id.rb_pay_sms).isChecked = true
        findViewById<EditText>(R.id.et_pay_sender).setText(com.noti.logger.reminder.RuleAutoParser.senderFilter(sender))
        findViewById<EditText>(R.id.et_pay_sample).setText(sample)
        // The message is the authority here (the user confirmed replacing): clear what an earlier
        // setup left, or auto-fill - which only fills empty fields - would keep the old patterns.
        findViewById<EditText>(R.id.et_pay_match).setText("")
        findViewById<EditText>(R.id.et_pay_amount).setText("")
        updatePayUi()
        autoFillPayment()
        // Bring the section into view - it is at the bottom of a long form.
        findViewById<View>(R.id.card_pay).post {
            (findViewById<View>(R.id.card_pay).parent?.parent as? android.widget.ScrollView)
                ?.smoothScrollTo(0, findViewById<View>(R.id.card_pay).top)
        }
    }

    /** As [autoFill], for the payment side: only fields still empty are filled. */
    private fun autoFillPayment() {
        val sample = findViewById<EditText>(R.id.et_pay_sample).text.toString()
        if (sample.isBlank()) {
            Toast.makeText(this, R.string.rm_auto_nothing, Toast.LENGTH_LONG).show()
            return
        }
        val d = com.noti.logger.reminder.RuleAutoParser.draftPayment(sample)
        var filled = 0
        for ((id, value) in listOf(R.id.et_pay_match to d.matchPattern, R.id.et_pay_amount to d.amountPattern)) {
            if (value != null && text(id).isBlank()) { findViewById<EditText>(id).setText(value); filled++ }
        }
        Toast.makeText(
            this,
            if (filled > 0) getString(R.string.rm_auto_filled, filled) else getString(R.string.rm_auto_nothing),
            Toast.LENGTH_LONG,
        ).show()
        runPayTest()
    }

    /** What the scan would make of the sample: a payment (and how much), the statement itself, or
     *  nothing - checked the same way [com.noti.logger.reminder.ReminderScanner] checks it. */
    private fun runPayTest() {
        val out = findViewById<TextView>(R.id.tv_pay_test_result)
        val sample = findViewById<EditText>(R.id.et_pay_sample).text.toString()
        if (sample.isBlank()) return
        for ((label, id) in listOf(
            getString(R.string.rm_pay_match) to R.id.et_pay_match,
            getString(R.string.rm_pay_amount) to R.id.et_pay_amount,
        )) {
            val err = ReminderExtractor.patternError(text(id))
            if (err != null) {
                out.text = getString(R.string.rm_bad_pattern, "$label - $err")
                out.setTextColor(MaterialColors.getColor(out, androidx.appcompat.R.attr.colorError))
                return
            }
        }
        out.setTextColor(MaterialColors.getColor(out, com.google.android.material.R.attr.colorOnSurfaceVariant))
        val pattern = text(R.id.et_pay_match)
        out.text = when {
            pattern.isBlank() || !ReminderExtractor.matches(pattern, sample) -> getString(R.string.rm_test_no_match)
            text(R.id.et_match).isNotBlank() && ReminderExtractor.matches(text(R.id.et_match), sample) &&
                sourceType() != SourceType.MANUAL -> getString(R.string.rm_pay_test_statement)
            else -> {
                val raw = ReminderExtractor.capture(text(R.id.et_pay_amount), sample)
                val amount = com.noti.logger.reminder.ReminderPolicy.parseAmount(raw)
                getString(R.string.rm_pay_test_result, amount?.toString() ?: raw?.let { "$it (unreadable)" } ?: getString(R.string.cs_unknown))
            }
        }
    }

    // ---- The sample field ----

    /**
     * Selecting a value in the sample used to raise the keyboard, which shoved the page up and the
     * selection handles with it - mid-gesture. Focus no longer shows it; the keyboard icon on the
     * field turns typing on for when a sample needs pasting or editing.
     *
     * The field choices also sit in the text-selection bar itself, so the usual path is now select
     * → tap "Amount" right there, instead of selecting and then finding the button below.
     */
    private fun setUpSampleField(
        fieldId: Int,
        layoutId: Int,
        choices: () -> List<Triple<Int, PatternBuilder.Field, Int>>,
    ) {
        val field = findViewById<EditText>(fieldId)
        field.showSoftInputOnFocus = false
        findViewById<com.google.android.material.textfield.TextInputLayout>(layoutId).setEndIconOnClickListener {
            field.showSoftInputOnFocus = true
            field.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(field, 0)
        }
        field.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                choices().forEachIndexed { i, (label, _, _) ->
                    menu.add(android.view.Menu.NONE, SEL_MENU_BASE + i, i, getString(label))
                }
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val fields = choices()
                val i = item.itemId - SEL_MENU_BASE
                if (i !in fields.indices) return false
                val lo = minOf(field.selectionStart, field.selectionEnd)
                val hi = maxOf(field.selectionStart, field.selectionEnd)
                if (lo == hi) return false
                applySelection(field.text.toString(), lo, hi, fields[i].second, fields[i].third)
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }
    }

    private fun applyDefaults() {
        findViewById<RadioButton>(R.id.rb_pay_sms).isChecked = true
        findViewById<RadioButton>(R.id.rb_type_bill).isChecked = true
        findViewById<RadioButton>(R.id.rb_source_notification).isChecked = true
        findViewById<RadioButton>(R.id.rb_due_message).isChecked = true
        findViewById<RadioButton>(R.id.rb_rec_monthly).isChecked = true
        findViewById<EditText>(R.id.et_constant_day).setText("1")
        findViewById<EditText>(R.id.et_anchor_day).setText("1")
        findViewById<EditText>(R.id.et_offset_days).setText("0")
        updateTypeUi()
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PICKED, pickedPackage)
        outState.putString(STATE_PAY_PICKED, payPackage)
        outState.putBoolean(STATE_PICKING_PAY, pickingForPayment)
    }

    /** [fillFields] = false after a rotation: only the rule itself is needed (for save and delete);
     *  the fields already hold what was being edited, which reloading would overwrite. */
    private fun load(fillFields: Boolean = true) {
        lifecycleScope.launch {
            val rule = withContext(Dispatchers.IO) {
                try { NotiDatabase.get(applicationContext).reminderRuleDao().byId(ruleId) } catch (e: Exception) { null }
            }
            if (rule == null) {
                if (fillFields) applyDefaults()
                return@launch
            }
            existing = rule
            if (!fillFields) {
                findViewById<View>(R.id.btn_delete).visibility = View.VISIBLE
                return@launch
            }
            pickedPackage = rule.sourcePackage
            findViewById<EditText>(R.id.et_name).setText(rule.name)
            findViewById<RadioButton>(
                if (RuleType.valueOf(rule.type) == RuleType.PARKING) R.id.rb_type_parking else R.id.rb_type_bill
            ).isChecked = true
            findViewById<RadioButton>(
                when (SourceType.valueOf(rule.source)) {
                    SourceType.SMS -> R.id.rb_source_sms
                    SourceType.MANUAL -> R.id.rb_source_manual
                    else -> R.id.rb_source_notification
                }
            ).isChecked = true
            findViewById<EditText>(R.id.et_sender).setText(rule.sourceSender)
            findViewById<MaterialSwitch>(R.id.sw_rule_enabled).isChecked = rule.enabled == 1
            findViewById<EditText>(R.id.et_match).setText(rule.matchPattern)
            findViewById<EditText>(R.id.et_amount).setText(rule.amountPattern)
            findViewById<EditText>(R.id.et_currency).setText(rule.currencyPattern)
            findViewById<EditText>(R.id.et_date).setText(rule.datePattern)
            findViewById<EditText>(R.id.et_zone).setText(rule.zonePattern)
            findViewById<EditText>(R.id.et_expiry).setText(rule.expiryPattern)
            findViewById<EditText>(R.id.et_format).setText(rule.dateFormat)
            findViewById<RadioButton>(
                when (DueMode.valueOf(rule.dueMode)) {
                    DueMode.CONSTANT -> R.id.rb_due_constant
                    DueMode.CALCULATED -> R.id.rb_due_calculated
                    else -> R.id.rb_due_message
                }
            ).isChecked = true
            findViewById<EditText>(R.id.et_constant_day).setText(rule.constantDay.toString())
            findViewById<EditText>(R.id.et_anchor_day).setText(rule.anchorDay.toString())
            findViewById<EditText>(R.id.et_offset_days).setText(rule.offsetDays.toString())
            findViewById<RadioButton>(
                when (Recurrence.valueOf(rule.recurrence)) {
                    Recurrence.WEEKLY -> R.id.rb_rec_weekly
                    Recurrence.YEARLY -> R.id.rb_rec_yearly
                    else -> R.id.rb_rec_monthly
                }
            ).isChecked = true
            findViewById<EditText>(R.id.et_default_currency).setText(rule.defaultCurrency)
            findViewById<View>(R.id.btn_delete).visibility = View.VISIBLE
            findViewById<MaterialSwitch>(R.id.sw_pay_enabled).isChecked = rule.payEnabled == 1
            findViewById<RadioButton>(
                if (rule.paySource == SourceType.NOTIFICATION.name) R.id.rb_pay_app else R.id.rb_pay_sms
            ).isChecked = true
            payPackage = rule.payPackage
            findViewById<EditText>(R.id.et_pay_sender).setText(rule.paySender)
            findViewById<EditText>(R.id.et_pay_match).setText(rule.payMatchPattern)
            findViewById<EditText>(R.id.et_pay_amount).setText(rule.payAmountPattern)
            findViewById<MaterialSwitch>(R.id.sw_pay_match_amount).isChecked = rule.payMatchAmount == 1
            updateTypeUi()
            updatePayUi()
            // Long-press → "Use as payment for a rule" lands here with the confirmation message.
            intent.getStringExtra(EXTRA_PAY_SAMPLE)?.takeIf { it.isNotBlank() }?.let {
                prefillPayment(it, intent.getStringExtra(EXTRA_PAY_SENDER).orEmpty())
            }
        }
    }

    /**
     * Writes a pattern from what the user selected in the sample.
     *
     * This is the path most people should take. Selecting the amount in a real message and saying
     * "that one" carries the piece of information that is actually hard to infer, and
     * [PatternBuilder] verifies its own output against the sample before it lands in the field - so
     * a pattern that would have grabbed the wrong number is declined rather than offered.
     */
    private fun buildFromSelection(anchor: View) {
        val sampleField = findViewById<EditText>(R.id.et_sample)
        val sample = sampleField.text.toString()
        val start = sampleField.selectionStart
        val end = sampleField.selectionEnd
        if (sample.isBlank() || start == end) {
            Toast.makeText(this, R.string.rm_select_first, Toast.LENGTH_LONG).show()
            return
        }
        val lo = minOf(start, end)
        val hi = maxOf(start, end)

        val fields = selectionFields()

        val menu = android.widget.PopupMenu(this, anchor)
        fields.forEachIndexed { i, (labelRes, _, _) -> menu.menu.add(0, i, i, getString(labelRes)) }
        menu.setOnMenuItemClickListener { item ->
            val (_, field, target) = fields[item.itemId]
            applySelection(sample, lo, hi, field, target)
            true
        }
        menu.show()
    }

    /** Only the fields this rule type actually has, so nobody is offered "Amount" on a parking rule
     *  and left wondering where it went. Shared by the button's menu and the selection bar. */
    private fun selectionFields(): List<Triple<Int, PatternBuilder.Field, Int>> =
        if (isParking()) {
            listOf(
                Triple(R.string.rm_field_identify, PatternBuilder.Field.IDENTIFY, R.id.et_match),
                Triple(R.string.rm_pattern_zone, PatternBuilder.Field.ZONE, R.id.et_zone),
                Triple(R.string.rm_pattern_expiry, PatternBuilder.Field.EXPIRY, R.id.et_expiry),
            )
        } else {
            listOf(
                Triple(R.string.rm_field_identify, PatternBuilder.Field.IDENTIFY, R.id.et_match),
                Triple(R.string.rm_pattern_amount, PatternBuilder.Field.AMOUNT, R.id.et_amount),
                Triple(R.string.rm_pattern_currency, PatternBuilder.Field.CURRENCY, R.id.et_currency),
                Triple(R.string.rm_pattern_date, PatternBuilder.Field.DATE, R.id.et_date),
            )
        }

    /**
     * Builds a pattern for one field from the selected span and puts it in that field. Separated
     * from the menu so the whole path - build, verify, fill, re-test - can be exercised without
     * driving a popup.
     *
     * Returns whether a pattern was produced; a refusal fills nothing, because a field left empty
     * is honest where a wrong pattern quietly is not.
     */
    internal fun applySelection(
        sample: String,
        start: Int,
        end: Int,
        field: PatternBuilder.Field,
        targetId: Int,
    ): Boolean {
        val result = PatternBuilder.forSelection(sample, start, end, field)
        if (result == null) {
            Toast.makeText(this, R.string.rm_built_failed, Toast.LENGTH_LONG).show()
            return false
        }
        findViewById<EditText>(targetId).setText(result.pattern)
        // A recognised date shape fills the format too - it is the field people forget, and getting
        // it wrong is the commonest reason a rule matches but produces no date.
        result.dateFormat?.let { findViewById<EditText>(R.id.et_format).setText(it) }
        Toast.makeText(
            this,
            if (result.dateFormat != null) R.string.rm_built_ok_format else R.string.rm_built_ok,
            Toast.LENGTH_SHORT,
        ).show()
        if (targetId == R.id.et_pay_match || targetId == R.id.et_pay_amount) runPayTest() else runTest()
        return true
    }

    // ---- The guide, inline ----

    /**
     * The worked example and the cheat-sheet live in this screen, not on a page of their own: you
     * cannot read a separate screen and type a regex into this one at the same time, which is the
     * only moment the guidance is worth anything.
     *
     * It starts collapsed so it doesn't bury the fields for someone who already knows what they're
     * doing, and the per-field examples below stay visible either way - those are the part you
     * actually reach for, and tapping one fills the field in, which beats retyping a regex on a
     * phone keyboard.
     */
    private fun toggleHelp() {
        val container = findViewById<LinearLayout>(R.id.help_container)
        val button = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_toggle_help)
        val showing = container.visibility == View.VISIBLE
        if (showing) {
            container.visibility = View.GONE
            button.setText(R.string.rm_help_show)
            return
        }
        if (container.childCount == 0) buildHelp(container)
        container.visibility = View.VISIBLE
        button.setText(R.string.rm_help_hide)
    }

    /** Built lazily and rebuilt when the rule type changes, so a parking rule is never taught the
     *  bill fields and vice versa. */
    private fun buildHelp(container: LinearLayout) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        fun block(title: String, body: String, code: String? = null) {
            val v = inflater.inflate(R.layout.item_help_block, container, false)
            v.findViewById<TextView>(R.id.tv_block_title).text = title
            v.findViewById<TextView>(R.id.tv_block_body).text = body
            v.findViewById<TextView>(R.id.tv_block_code).apply {
                if (code == null) visibility = View.GONE else { text = code; visibility = View.VISIBLE }
            }
            container.addView(v)
        }

        if (isParking()) {
            block(
                "Start from a real ticket",
                "Paste an actual parking notification into the test box at the bottom, then press " +
                    "Test to see exactly what each pattern pulled out. The field examples below " +
                    "are written against this one:",
                "Parking started. Zone: ZONE-4B North Lot.\nYour session is valid until 14:30.",
            )
            block(
                "Two fields",
                "One pattern finds the zone, one finds the expiry. Each keeps whatever is inside " +
                    "its brackets ( ). If a ticket only gives a time that is fine - it is taken as " +
                    "today, rolling to tomorrow if the time has already passed.",
            )
        } else {
            block(
                "Start from a real message",
                "Paste an actual statement into the test box at the bottom, then press Test to see " +
                    "exactly what each pattern pulled out. The field examples below are written " +
                    "against this one:",
                "Dear Customer, your HDFC Bank Credit Card\nstatement for Jan 2026 is generated.\n" +
                    "Total Amount Due INR 12,480.50.\nPayment due by 07-Feb-2026.",
            )
            block(
                "Four fields",
                "One pattern decides the message is yours, then one each for the amount, the " +
                    "currency and the due date. Each keeps whatever is inside its brackets ( ). " +
                    "Keeping them separate means a bank changing one line only breaks one field.",
            )
        }

        block(
            "The pieces you'll actually use",
            "\\d   a digit\n" +
                "\\w   a letter or digit\n" +
                "\\s   a space\n" +
                "+    one or more of the thing before it\n" +
                "*    any number of them, including none\n" +
                "{2}  exactly two of them\n" +
                ".    any character at all\n" +
                ".*   anything, of any length\n" +
                "( )  the part you want to keep\n" +
                "|    or, as in (INR|AED)",
        )
        block(
            "Matching is forgiving",
            "Case is ignored, so \"DUE BY\" and \"due by\" both match, and a pattern can run across " +
                "line breaks - which matters for notifications where the amount and the date sit " +
                "on different lines.",
        )
        block(
            "If nothing matches",
            "Press Test with a sample pasted in. \"No match\" means the identify pattern is the " +
                "problem - shorten it until it matches, then tighten it. If it matches but a field " +
                "comes back with a dash, only that field's pattern is wrong; the rest still work.",
        )
        if (!isParking()) {
            block(
                "Due dates with no date in the message",
                "Some bills never state one. \"Same day each month\" is for a bill always due on, " +
                    "say, the 15th. \"Days after a fixed day\" is how card statements work: " +
                    "statement on the 10th, payment due 28 days later.",
            )
        }
    }

    /** The example under each field, and tapping it fills the field in. */
    private fun applyFieldExamples() {
        val parking = isParking()
        val examples = listOf(
            R.id.tv_eg_match to (if (parking) "parking" else "statement.*generated"),
            R.id.tv_eg_amount to """Total Amount Due\s+(?:INR|₹)\s*([\d,]+\.?\d*)""",
            R.id.tv_eg_currency to """\b(INR|AED|USD)\b""",
            R.id.tv_eg_date to """due by\s+(\d{2}-\w{3}-\d{4})""",
            R.id.tv_eg_zone to """Zone:\s*([\w\- ]+?)\s*\.""",
            R.id.tv_eg_expiry to """valid until\s+(\d{2}:\d{2})""",
            R.id.tv_eg_format to (if (parking) "HH:mm" else "dd-MMM-yyyy"),
        )
        val targets = mapOf(
            R.id.tv_eg_match to R.id.et_match, R.id.tv_eg_amount to R.id.et_amount,
            R.id.tv_eg_currency to R.id.et_currency, R.id.tv_eg_date to R.id.et_date,
            R.id.tv_eg_zone to R.id.et_zone, R.id.tv_eg_expiry to R.id.et_expiry,
            R.id.tv_eg_format to R.id.et_format,
        )
        for ((viewId, sample) in examples) {
            val tv = findViewById<TextView>(viewId) ?: continue
            tv.text = getString(R.string.rm_eg_prefix, sample)
            tv.setOnClickListener {
                findViewById<EditText>(targets.getValue(viewId)).setText(sample)
                runTest()
            }
        }
    }

    /**
     * Offer only apps whose notifications are actually captured. A rule pointed at an app outside
     * the capture allowlist can never match - the notification is dropped before it is ever stored -
     * and it would fail silently, forever, with nothing to see. An empty allowlist means "capture
     * everything", in which case every launchable app is a legitimate target.
     */
    private fun pickApp() {
        // Called for the payment source with the flag already set; the bill's button clears it.
        val captured = Settings.get(this).includedPackages
        if (captured.isEmpty()) {
            appPicker.launch(Intent(this, AppPickerActivity::class.java))
            return
        }
        appPicker.launch(
            Intent(this, AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_LIMIT_TO, captured.toTypedArray())
        )
    }

    /**
     * Warn when the rule watches an app that isn't being captured. This can happen to a rule that
     * was fine when it was written and was orphaned later by a change to the capture selection, so
     * the check is on render rather than only at pick time.
     */
    private fun refreshSourceWarning() {
        val warn = findViewById<TextView>(R.id.tv_source_warning) ?: return
        val captured = Settings.get(this).includedPackages
        val orphaned = sourceType() == SourceType.NOTIFICATION &&
            pickedPackage.isNotBlank() && captured.isNotEmpty() && pickedPackage !in captured
        warn.visibility = if (orphaned) View.VISIBLE else View.GONE
        if (orphaned) {
            warn.text = getString(R.string.rm_source_not_captured, pickedPackage)
            warn.setTextColor(MaterialColors.getColor(warn, androidx.appcompat.R.attr.colorError))
        }
    }

    // ---- Visibility ----

    private fun isParking() = findViewById<RadioButton>(R.id.rb_type_parking).isChecked

    private fun sourceType(): SourceType = when {
        findViewById<RadioButton>(R.id.rb_source_sms).isChecked -> SourceType.SMS
        findViewById<RadioButton>(R.id.rb_source_manual).isChecked -> SourceType.MANUAL
        else -> SourceType.NOTIFICATION
    }

    private fun updateTypeUi() {
        val parking = isParking()
        updatePayUi()
        // Each field and its example line travel together - the examples are siblings, not
        // children, so hiding the field alone would leave a parking example under a bill rule.
        val billOnly = if (parking) View.GONE else View.VISIBLE
        val parkingOnly = if (parking) View.VISIBLE else View.GONE
        for (id in listOf(R.id.til_amount, R.id.tv_eg_amount, R.id.til_currency, R.id.tv_eg_currency,
                          R.id.til_date, R.id.tv_eg_date)) {
            findViewById<View>(id).visibility = billOnly
        }
        for (id in listOf(R.id.til_zone, R.id.tv_eg_zone, R.id.til_expiry, R.id.tv_eg_expiry)) {
            findViewById<View>(id).visibility = parkingOnly
        }
        // A parking session has no recurrence and no due-date scheme - it starts when a ticket
        // arrives and ends when you end it.
        findViewById<View>(R.id.card_due).visibility = if (parking) View.GONE else View.VISIBLE
        // Nor can it be manual: with no message there is nothing to start a session from. A one-off
        // parking session is added from the Reminders tab instead.
        findViewById<View>(R.id.rb_source_manual).visibility = if (parking) View.GONE else View.VISIBLE
        if (parking && sourceType() == SourceType.MANUAL) {
            findViewById<RadioButton>(R.id.rb_source_notification).isChecked = true
        }
        updateSourceUi()
        updateDueUi()
        applyFieldExamples()
        // The guide is type-specific, so a visible one is rebuilt rather than left describing the
        // fields the user just switched away from.
        val help = findViewById<LinearLayout>(R.id.help_container)
        if (help.visibility == View.VISIBLE) buildHelp(help) else help.removeAllViews()
    }

    private fun updateSourceUi() {
        val source = sourceType()
        val pick = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pick_app)
        pick.visibility = if (source == SourceType.NOTIFICATION) View.VISIBLE else View.GONE
        pick.text = if (pickedPackage.isBlank()) getString(R.string.rm_rule_source_pick) else pickedPackage
        findViewById<View>(R.id.til_sender).visibility = if (source == SourceType.SMS) View.VISIBLE else View.GONE
        refreshSourceWarning()
        // A manual rule matches nothing, so every pattern field is noise.
        findViewById<View>(R.id.card_patterns).visibility = if (source == SourceType.MANUAL) View.GONE else View.VISIBLE
    }

    private fun updateDueUi() {
        val mode = when {
            findViewById<RadioButton>(R.id.rb_due_constant).isChecked -> DueMode.CONSTANT
            findViewById<RadioButton>(R.id.rb_due_calculated).isChecked -> DueMode.CALCULATED
            else -> DueMode.FROM_MESSAGE
        }
        findViewById<View>(R.id.til_constant_day).visibility = if (mode == DueMode.CONSTANT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.til_anchor_day).visibility = if (mode == DueMode.CALCULATED) View.VISIBLE else View.GONE
        findViewById<View>(R.id.til_offset_days).visibility = if (mode == DueMode.CALCULATED) View.VISIBLE else View.GONE
        // Recurrence only means anything when nothing is matched to drive the dates.
        val manual = sourceType() == SourceType.MANUAL
        findViewById<View>(R.id.tv_recurrence_label).visibility = if (manual) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rg_recurrence).visibility = if (manual) View.VISIBLE else View.GONE
    }

    // ---- Test ----

    private fun text(id: Int): String = findViewById<EditText>(id).text.toString().trim()

    private fun runTest() {
        val out = findViewById<TextView>(R.id.tv_test_result)
        val sample = findViewById<EditText>(R.id.et_sample).text.toString()
        if (sample.isBlank()) return

        // Report a broken pattern by name before running anything, so the failure is attributable.
        for ((label, id) in listOf(
            getString(R.string.rm_pattern_match) to R.id.et_match,
            getString(R.string.rm_pattern_amount) to R.id.et_amount,
            getString(R.string.rm_pattern_currency) to R.id.et_currency,
            getString(R.string.rm_pattern_date) to R.id.et_date,
            getString(R.string.rm_pattern_zone) to R.id.et_zone,
            getString(R.string.rm_pattern_expiry) to R.id.et_expiry,
        )) {
            val err = ReminderExtractor.patternError(text(id))
            if (err != null) {
                out.text = getString(R.string.rm_bad_pattern, "$label - $err")
                out.setTextColor(MaterialColors.getColor(out, androidx.appcompat.R.attr.colorError))
                return
            }
        }
        out.setTextColor(MaterialColors.getColor(out, com.google.android.material.R.attr.colorOnSurfaceVariant))

        val dash = getString(R.string.cs_unknown)
        if (isParking()) {
            val m = ReminderExtractor.extractParking(
                sample, text(R.id.et_match), text(R.id.et_zone), text(R.id.et_expiry), text(R.id.et_format),
            )
            out.text = if (m == null) getString(R.string.rm_test_no_match) else getString(
                R.string.rm_test_result_parking,
                m.zone?.ifBlank { dash } ?: dash,
                m.expiry?.toString() ?: (m.expiryRaw?.let { "$it (unreadable)" } ?: dash),
            )
        } else {
            val m = ReminderExtractor.extractBill(
                sample, text(R.id.et_match), text(R.id.et_amount), text(R.id.et_currency),
                text(R.id.et_date), text(R.id.et_format),
            )
            out.text = if (m == null) getString(R.string.rm_test_no_match) else getString(
                R.string.rm_test_result,
                m.amount?.toString() ?: (m.amountRaw?.let { "$it (unreadable)" } ?: dash),
                m.currency?.ifBlank { dash } ?: dash,
                m.dueDate?.toString() ?: (m.dueDateRaw?.let { "$it (unreadable)" } ?: dash),
            )
        }
    }

    // ---- Save / delete ----

    private fun save() {
        val name = text(R.id.et_name)
        if (name.isBlank()) {
            Toast.makeText(this, R.string.rm_rule_needs_name, Toast.LENGTH_SHORT).show()
            return
        }
        // A pattern that won't compile, or that runs away on long text, never reaches the scan.
        for (id in listOf(R.id.et_match, R.id.et_amount, R.id.et_currency, R.id.et_date, R.id.et_zone,
            R.id.et_expiry, R.id.et_pay_match, R.id.et_pay_amount)) {
            val err = ReminderExtractor.patternError(text(id)) ?: continue
            findViewById<EditText>(id).error = err
            Toast.makeText(this, getString(R.string.rm_bad_pattern, err), Toast.LENGTH_LONG).show()
            return
        }
        val payOn = payEnabled()
        val payFromApp = findViewById<RadioButton>(R.id.rb_pay_app).isChecked
        if (payOn && text(R.id.et_pay_match).isBlank()) {
            Toast.makeText(this, R.string.rm_pay_needs_match, Toast.LENGTH_LONG).show()
            return
        }
        if (payOn && payFromApp && payPackage.isBlank()) {
            Toast.makeText(this, R.string.rm_pay_needs_app, Toast.LENGTH_LONG).show()
            return
        }
        // Payments arriving from the same place as the statements need the bill's own match
        // pattern to tell the two apart: with it blank, every payment message would also be read
        // as a new bill, and a statement saying "payment due" could settle itself.
        val sameSource = if (payFromApp) {
            sourceType() == SourceType.NOTIFICATION && payPackage == pickedPackage
        } else {
            val bill = text(R.id.et_sender); val pay = text(R.id.et_pay_sender)
            sourceType() == SourceType.SMS && (bill.isBlank() || pay.isBlank() ||
                bill.contains(pay, ignoreCase = true) || pay.contains(bill, ignoreCase = true))
        }
        if (payOn && !isParking() && sameSource && text(R.id.et_match).isBlank()) {
            Toast.makeText(this, R.string.rm_pay_needs_bill_match, Toast.LENGTH_LONG).show()
            return
        }
        val rule = ReminderRuleEntity(
            id = ruleId,
            name = name,
            type = (if (isParking()) RuleType.PARKING else RuleType.BILL).name,
            source = sourceType().name,
            sourcePackage = if (sourceType() == SourceType.NOTIFICATION) pickedPackage else "",
            sourceSender = if (sourceType() == SourceType.SMS) text(R.id.et_sender) else "",
            enabled = if (findViewById<MaterialSwitch>(R.id.sw_rule_enabled).isChecked) 1 else 0,
            matchPattern = text(R.id.et_match),
            amountPattern = text(R.id.et_amount),
            currencyPattern = text(R.id.et_currency),
            datePattern = text(R.id.et_date),
            zonePattern = text(R.id.et_zone),
            expiryPattern = text(R.id.et_expiry),
            dateFormat = text(R.id.et_format),
            dueMode = when {
                findViewById<RadioButton>(R.id.rb_due_constant).isChecked -> DueMode.CONSTANT
                findViewById<RadioButton>(R.id.rb_due_calculated).isChecked -> DueMode.CALCULATED
                else -> DueMode.FROM_MESSAGE
            }.name,
            constantDay = text(R.id.et_constant_day).toIntOrNull()?.coerceIn(1, 31) ?: 1,
            anchorDay = text(R.id.et_anchor_day).toIntOrNull()?.coerceIn(1, 31) ?: 1,
            offsetDays = text(R.id.et_offset_days).toIntOrNull()?.coerceIn(0, 365) ?: 0,
            recurrence = when {
                findViewById<RadioButton>(R.id.rb_rec_weekly).isChecked -> Recurrence.WEEKLY
                findViewById<RadioButton>(R.id.rb_rec_yearly).isChecked -> Recurrence.YEARLY
                else -> Recurrence.MONTHLY
            }.name,
            defaultCurrency = text(R.id.et_default_currency),
            // Carried over, not reset: an edit to a rule's regex must not silently clear a
            // "skip next" the user set last week. The same goes for everything this form doesn't
            // show - a repeating bill made from the Add screen has a start date, an "every N", an
            // amount, notes, a category and its own lead, and saving here used to wipe them all.
            skipNext = existing?.skipNext ?: 0,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            recurrenceEvery = existing?.recurrenceEvery ?: 1,
            anchorDate = existing?.anchorDate ?: 0,
            defaultAmount = existing?.defaultAmount,
            notes = existing?.notes ?: "",
            category = existing?.category ?: "",
            remindDaysBefore = existing?.remindDaysBefore ?: -1,
            payEnabled = if (payOn) 1 else 0,
            paySource = (if (payFromApp) SourceType.NOTIFICATION else SourceType.SMS).name,
            paySender = if (payFromApp) "" else text(R.id.et_pay_sender),
            payPackage = if (payFromApp) payPackage else "",
            payMatchPattern = text(R.id.et_pay_match),
            payAmountPattern = text(R.id.et_pay_amount),
            payMatchAmount = if (findViewById<MaterialSwitch>(R.id.sw_pay_match_amount).isChecked) 1 else 0,
        )
        // One save per tap: a double tap used to insert the rule (or bill) twice.
        if (saving) return
        saving = true
        lifecycleScope.launch {
            val settled = withContext(Dispatchers.IO) {
                val dao = NotiDatabase.get(applicationContext).reminderRuleDao()
                val id = if (ruleId > 0) { dao.update(rule); ruleId } else dao.insert(rule)
                // A manual rule should produce its first occurrence straight away rather than
                // waiting for the next scheduled scan.
                com.noti.logger.reminder.ReminderTrigger.invalidateWatched()
                com.noti.logger.reminder.ReminderScanner.rollManualRules(applicationContext)
                com.noti.logger.reminder.ReminderScheduler.rebuildAll(applicationContext)
                // The payment this was set up from has usually been scanned already; look back now
                // so the bill it pays is marked, rather than never.
                if (payOn) com.noti.logger.reminder.ReminderScanner.resolvePaymentsFor(applicationContext, id) else 0
            }
            Toast.makeText(
                this@ReminderRuleActivity,
                if (settled > 0) resources.getQuantityString(R.plurals.rm_pay_settled_now, settled, settled)
                else getString(R.string.settings_saved),
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.rm_delete_rule_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rm_delete_rule) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NotiDatabase.get(applicationContext).reminderRuleDao().delete(ruleId)
                    }
                    Toast.makeText(this@ReminderRuleActivity, R.string.rm_rule_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .show()
    }
}
