package com.noti.logger.ui

import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.noti.logger.R
import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ReminderRuleEntity
import com.noti.logger.reminder.DueMode
import com.noti.logger.reminder.ParkingTracker
import com.noti.logger.reminder.Recurrence
import com.noti.logger.reminder.ReminderPolicy
import com.noti.logger.reminder.ReminderScanner
import com.noti.logger.reminder.ReminderScheduler
import com.noti.logger.reminder.RuleType
import com.noti.logger.reminder.SourceType
import com.noti.logger.util.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Adds a reminder by hand, and edits one that already exists.
 *
 * Dates are picked, never typed. The fields here used to be text with a `dd/MM/yyyy` hint, which
 * on a numeric keypad cannot produce a slash - the format was unenterable, so the field was simply
 * broken. A picker also removes every ambiguity about day-month order.
 *
 * A manual bill can repeat, and repeating turns it into a MANUAL rule rather than a single row:
 * that is the machinery that already tops itself up as each occurrence is settled, so a monthly
 * bill never has to be recreated.
 */
class ReminderEditActivity : ScreenActivity() {

    companion object {
        const val EXTRA_BILL_ID = "bill_id"
        const val EXTRA_PARKING_ID = "parking_id"
    }

    override val layoutRes = R.layout.activity_reminder_edit
    override val titleRes = R.string.title_reminder_edit

    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

    private var billId = 0L
    private var parkingId = 0L
    private var existingBill: BillOccurrenceEntity? = null

    /** The picked values, held as real dates rather than parsed back out of text. */
    private var dueDate: LocalDate? = null
    private var expiry: LocalDateTime? = null
    private var recurrence = Recurrence.NEVER

    /** Editing one occurrence of a bill: its rule owns the recurrence, so the repeat controls hide. */
    private var editingOccurrence = false

    override fun onScreenCreated() {
        billId = intent.getLongExtra(EXTRA_BILL_ID, 0L)
        parkingId = intent.getLongExtra(EXTRA_PARKING_ID, 0L)

        findViewById<MaterialButtonToggleGroup>(R.id.toggle_kind).addOnButtonCheckedListener { _, _, checked ->
            if (checked) updateKindUi()
        }
        // The date fields are pickers wearing a field's clothes: a tap on the text or on the
        // trailing icon opens the dialog.
        findViewById<View>(R.id.et_due).setOnClickListener { pickDueDate() }
        findViewById<TextInputLayout>(R.id.til_due_date).setEndIconOnClickListener { pickDueDate() }
        findViewById<View>(R.id.et_expiry).setOnClickListener { pickExpiry() }
        findViewById<TextInputLayout>(R.id.til_expiry).setEndIconOnClickListener { pickExpiry() }
        setUpRepeatDropdown()
        findViewById<MaterialSwitch>(R.id.sw_due_calculated).setOnCheckedChangeListener { _, on ->
            Motion.animateNextLayout(findViewById(R.id.form))
            findViewById<View>(R.id.group_calculated).visibility = if (on) View.VISIBLE else View.GONE
            refreshDueUi()
        }
        // On every keystroke, not on focus loss: dismissing the keyboard does not always move
        // focus, so a focus-based update left the preview showing the previous interval. A preview
        // that lags is worse than none - its whole job is to catch a wrong number before it is
        // saved, and it cannot do that while it is describing the last value.
        findViewById<EditText>(R.id.et_repeat_every).doAfterTextChanged { refreshDueUi() }
        findViewById<EditText>(R.id.et_offset_days).doAfterTextChanged { refreshDueUi() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }

        when {
            billId > 0 -> loadBill()
            parkingId > 0 -> loadParking()
            else -> {
                findViewById<MaterialButtonToggleGroup>(R.id.toggle_kind).check(R.id.btn_kind_bill)
                updateKindUi()
            }
        }
        refreshDueUi()
    }

    private fun isParking() =
        findViewById<MaterialButtonToggleGroup>(R.id.toggle_kind).checkedButtonId == R.id.btn_kind_parking

    private fun updateKindUi() {
        val parking = isParking()
        // Switching kind swaps whole groups of fields; let the cards resize rather than jump.
        Motion.animateNextLayout(findViewById(R.id.form))
        for (id in listOf(R.id.row_amount, R.id.til_due, R.id.card_optional)) {
            findViewById<View>(id).visibility = if (parking) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.til_zone).visibility = if (parking) View.VISIBLE else View.GONE
        findViewById<View>(R.id.til_expiry).visibility = if (parking) View.VISIBLE else View.GONE
        // Editing an existing entry cannot change what kind of thing it is.
        val editing = billId > 0 || parkingId > 0
        findViewById<View>(R.id.toggle_kind).visibility = if (editing) View.GONE else View.VISIBLE
    }

    // ---- Pickers ----

    private fun pickDueDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.rm_manual_due_label)
            .setSelection(dueDate?.let { it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
                ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            // The picker works in UTC by contract; converting through UTC keeps the day the user
            // tapped, which a local-zone conversion would shift either side of midnight.
            dueDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            refreshDueUi()
        }
        picker.show(supportFragmentManager, "due")
    }

    private fun pickExpiry() {
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.rm_manual_expiry_label)
            .setSelection(expiry?.toLocalDate()?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        datePicker.addOnPositiveButtonClickListener { utcMillis ->
            val date = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val existing = expiry
            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(existing?.hour ?: LocalDateTime.now().hour)
                .setMinute(existing?.minute ?: 0)
                .build()
            timePicker.addOnPositiveButtonClickListener {
                expiry = date.atTime(timePicker.hour, timePicker.minute)
                refreshDueUi()
            }
            timePicker.show(supportFragmentManager, "expiryTime")
        }
        datePicker.show(supportFragmentManager, "expiryDate")
    }

    /** The repeat choices, in menu order, with the dropdown's label for each. */
    private val repeatOptions = listOf(
        Recurrence.NEVER to R.string.rm_unit_never,
        Recurrence.DAILY to R.string.rm_repeat_daily,
        Recurrence.WEEKLY to R.string.rm_repeat_weekly,
        Recurrence.MONTHLY to R.string.rm_repeat_monthly,
        Recurrence.YEARLY to R.string.rm_repeat_yearly,
    )

    private fun setUpRepeatDropdown() {
        val dropdown = findViewById<MaterialAutoCompleteTextView>(R.id.dd_repeat)
        dropdown.setSimpleItems(repeatOptions.map { getString(it.second) }.toTypedArray())
        dropdown.setOnItemClickListener { _, _, position, _ ->
            recurrence = repeatOptions[position].first
            // A one-off has no cycle for "due N days after" to follow; don't leave it switched on
            // out of sight.
            if (recurrence == Recurrence.NEVER) findViewById<MaterialSwitch>(R.id.sw_due_calculated).isChecked = false
            Motion.animateNextLayout(findViewById(R.id.form))
            refreshDueUi()
        }
    }

    // ---- Live preview ----

    private fun repeatEvery(): Int =
        findViewById<EditText>(R.id.et_repeat_every).text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1

    private fun offsetDays(): Int =
        if (findViewById<MaterialSwitch>(R.id.sw_due_calculated).isChecked) {
            findViewById<EditText>(R.id.et_offset_days).text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        } else {
            0
        }

    /**
     * Keeps the buttons and the preview honest as the settings change. The preview matters more
     * than it looks: "raised on the 28th, due 10 days later, every month" is easy to set up and
     * hard to picture, and showing the next two dates is how a wrong offset gets noticed now rather
     * than when a reminder fires on the wrong day.
     */
    private fun refreshDueUi() {
        // Empty until picked, so the field shows its label rather than placeholder text.
        findViewById<EditText>(R.id.et_due).setText(dueDate?.format(dateFmt).orEmpty())
        findViewById<EditText>(R.id.et_expiry).setText(expiry?.format(dateTimeFmt).orEmpty())
        findViewById<MaterialAutoCompleteTextView>(R.id.dd_repeat).setText(
            getString(repeatOptions.first { it.first == recurrence }.second),
            false, // setting the value, not filtering the list
        )
        // "Repeat every [n]" only exists once something repeats, and says the unit it counts in.
        val repeats = recurrence != Recurrence.NEVER && !editingOccurrence
        findViewById<TextInputLayout>(R.id.til_repeat_every).apply {
            visibility = if (repeats) View.VISIBLE else View.GONE
            suffixText = when (recurrence) {
                Recurrence.NEVER -> null
                Recurrence.DAILY -> getString(R.string.rm_unit_day)
                Recurrence.WEEKLY -> getString(R.string.rm_unit_week)
                Recurrence.MONTHLY -> getString(R.string.rm_unit_month)
                Recurrence.YEARLY -> getString(R.string.rm_unit_year)
            }
        }
        // The calculated scheme only means something for something that recurs; a one-off is just
        // a date.
        findViewById<View>(R.id.sw_due_calculated).visibility = if (repeats) View.VISIBLE else View.GONE
        findViewById<TextInputLayout>(R.id.til_due_date).setHint(
            if (offsetDays() > 0) R.string.rm_anchor_day else R.string.rm_manual_due_label
        )

        val preview = findViewById<TextView>(R.id.tv_due_preview)
        val anchor = dueDate
        if (anchor == null) {
            preview.text = ""
            preview.visibility = View.GONE
            return
        }
        val today = LocalDate.now()
        val first = ReminderPolicy.nextManualDue(anchor, recurrence, repeatEvery(), offsetDays(), null, today)
        if (first == null) {
            preview.text = ""
            preview.visibility = View.GONE
            return
        }
        preview.visibility = View.VISIBLE
        val second = ReminderPolicy.nextManualDue(anchor, recurrence, repeatEvery(), offsetDays(), first, today)
        preview.text = if (second == null) {
            getString(R.string.rm_due_preview, first.format(dateFmt))
        } else {
            getString(R.string.rm_due_preview_repeat, first.format(dateFmt), second.format(dateFmt))
        }
    }

    // ---- Load ----

    private fun loadBill() {
        findViewById<MaterialButtonToggleGroup>(R.id.toggle_kind).check(R.id.btn_kind_bill)
        lifecycleScope.launch {
            val bill = withContext(Dispatchers.IO) {
                try { NotiDatabase.get(applicationContext).billOccurrenceDao().byId(billId) } catch (e: Exception) { null }
            } ?: return@launch
            existingBill = bill
            findViewById<EditText>(R.id.et_name).setText(bill.name)
            findViewById<EditText>(R.id.et_amount).setText(bill.amount?.toString() ?: bill.amountRaw)
            findViewById<EditText>(R.id.et_currency).setText(bill.currency)
            findViewById<EditText>(R.id.et_category).setText(bill.category)
            findViewById<EditText>(R.id.et_notes).setText(bill.notes)
            if (bill.remindDaysBefore >= 0) {
                findViewById<EditText>(R.id.et_remind_days).setText(bill.remindDaysBefore.toString())
            }
            if (bill.dueAt > 0) dueDate = ReminderPolicy.fromEpochMillis(bill.dueAt).toLocalDate()
            // An occurrence is one instance; its rule owns the recurrence, so editing here edits
            // this instance only and the repeat controls stay out of the way.
            editingOccurrence = true
            findViewById<View>(R.id.til_repeat).visibility = View.GONE
            // The rule is where the patterns, the schedule and the payment resolver live - one tap
            // away from the bill they produced. Hidden for a one-off, and for a rule since deleted.
            val ruleExists = bill.ruleId > 0 && withContext(Dispatchers.IO) {
                try { NotiDatabase.get(applicationContext).reminderRuleDao().byId(bill.ruleId) != null } catch (e: Exception) { false }
            }
            findViewById<View>(R.id.btn_edit_rule).apply {
                visibility = if (ruleExists) View.VISIBLE else View.GONE
                setOnClickListener {
                    startActivity(
                        android.content.Intent(this@ReminderEditActivity, ReminderRuleActivity::class.java)
                            .putExtra(ReminderRuleActivity.EXTRA_RULE_ID, bill.ruleId)
                    )
                }
            }
            updateKindUi()
            refreshDueUi()
        }
    }

    private fun loadParking() {
        findViewById<MaterialButtonToggleGroup>(R.id.toggle_kind).check(R.id.btn_kind_parking)
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) {
                try { NotiDatabase.get(applicationContext).parkingSessionDao().byId(parkingId) } catch (e: Exception) { null }
            } ?: return@launch
            findViewById<EditText>(R.id.et_name).setText(s.name)
            findViewById<EditText>(R.id.et_zone).setText(s.zone)
            if (s.expiresAt > 0) expiry = ReminderPolicy.fromEpochMillis(s.expiresAt)
            updateKindUi()
            refreshDueUi()
        }
    }

    // ---- Save ----

    private fun text(id: Int) = findViewById<EditText>(id).text.toString().trim()

    private fun save() {
        val name = text(R.id.et_name)
        if (name.isBlank()) {
            Toast.makeText(this, R.string.rm_rule_needs_name, Toast.LENGTH_SHORT).show()
            return
        }
        if (isParking()) saveParking(name) else saveBill(name)
    }

    private fun midnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun saveBill(name: String) {
        val anchor = dueDate
        if (anchor == null) {
            Toast.makeText(this, R.string.rm_needs_due, Toast.LENGTH_SHORT).show()
            return
        }
        val amount = ReminderPolicy.parseAmount(text(R.id.et_amount))
        val currency = text(R.id.et_currency)
        val category = text(R.id.et_category)
        val notes = text(R.id.et_notes)
        // Blank means "use the global setting", which is what -1 encodes.
        val lead = text(R.id.et_remind_days).toIntOrNull()?.coerceAtLeast(0) ?: ReminderPolicy.LEAD_FROM_GLOBAL
        val existing = existingBill
        val repeats = recurrence != Recurrence.NEVER && existing == null

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = NotiDatabase.get(applicationContext)
                if (existing != null) {
                    val dueAt = midnight(anchor)
                    db.billOccurrenceDao().update(
                        existing.copy(
                            name = name,
                            amount = amount,
                            amountRaw = text(R.id.et_amount),
                            currency = currency,
                            category = category,
                            notes = notes,
                            remindDaysBefore = lead,
                            dueAt = dueAt,
                            // A corrected date deserves a fresh reminder even if the old one
                            // already fired against the wrong day.
                            notified = if (existing.dueAt != dueAt) 0 else existing.notified,
                        )
                    )
                    // A repeating manual bill: a changed amount is next month's amount too, or the
                    // following occurrence would come out with the old one (or none) again.
                    if (existing.ruleId > 0 && amount != existing.amount) {
                        val rules = db.reminderRuleDao()
                        rules.byId(existing.ruleId)
                            ?.takeIf { it.source == SourceType.MANUAL.name }
                            ?.let { rules.update(it.copy(defaultAmount = amount)) }
                    }
                } else if (repeats) {
                    // A repeating manual bill is a MANUAL rule: that machinery already produces the
                    // next occurrence as each one is settled, so it never needs recreating by hand.
                    db.reminderRuleDao().insert(
                        ReminderRuleEntity(
                            name = name,
                            type = RuleType.BILL.name,
                            source = SourceType.MANUAL.name,
                            dueMode = if (offsetDays() > 0) DueMode.CALCULATED.name else DueMode.CONSTANT.name,
                            recurrence = recurrence.name,
                            recurrenceEvery = repeatEvery(),
                            anchorDate = midnight(anchor),
                            offsetDays = offsetDays(),
                            defaultCurrency = currency,
                            // The rule is what produces every occurrence, so the amount has to
                            // live on it - before, it was simply dropped and each bill came out blank.
                            defaultAmount = amount,
                            category = category,
                            notes = notes,
                            remindDaysBefore = lead,
                        )
                    )
                    ReminderScanner.rollManualRules(applicationContext)
                } else {
                    db.billOccurrenceDao().insert(
                        BillOccurrenceEntity(
                            ruleId = 0,
                            name = name,
                            dueAt = midnight(anchor),
                            amount = amount,
                            amountRaw = text(R.id.et_amount),
                            currency = currency,
                            category = category,
                            notes = notes,
                            remindDaysBefore = lead,
                            // A one-off has no source message to dedupe against; a clock-derived
                            // key keeps it clear of the unique index.
                            sourceKey = "manual-oneoff-${System.currentTimeMillis()}",
                        )
                    )
                }
                ReminderScheduler.rebuildAll(applicationContext)
            }
            finish()
        }
    }

    private fun saveParking(name: String) {
        val at = expiry
        if (parkingId == 0L && at == null) {
            Toast.makeText(this, R.string.rm_needs_due, Toast.LENGTH_SHORT).show()
            return
        }
        val zone = text(R.id.et_zone)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (parkingId > 0) {
                    ParkingTracker.setDetails(applicationContext, parkingId, zone, at)
                } else if (at != null) {
                    ParkingTracker.startManual(applicationContext, name, zone, at)
                }
            }
            finish()
        }
    }
}
