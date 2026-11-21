package com.noti.logger.ui

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.reminder.ParkingAlarm
import com.noti.logger.reminder.ParkingAlarmMode
import com.noti.logger.reminder.ReminderNotifier
import com.noti.logger.reminder.ReminderScheduler
import com.noti.logger.work.ReminderScanWorker
import java.util.Locale

/**
 * The module's central switch and everything that tunes it.
 *
 * The alarm section is deliberately a try-it-and-see: whether the phone's Clock app honours a
 * programmatic alarm, and whether Android lets us set one from the background, varies by OEM and
 * cannot be established from here. So the choice is a setting, both backends are built, and the
 * Test alarm / Cancel test pair exercises the selected one on the actual device rather than leaving
 * it to be discovered the first time a parking session matters.
 */
class ReminderSettingsActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_reminder_settings
    override val titleRes = R.string.title_reminder_settings

    override fun onScreenCreated() {
        val s = Settings.get(this)

        val enabled = findViewById<MaterialSwitch>(R.id.sw_reminder_enabled)
        val scanHours = findViewById<EditText>(R.id.et_scan_hours)
        val daysBefore = findViewById<EditText>(R.id.et_days_before)
        val remindTime = findViewById<EditText>(R.id.et_remind_time)
        val spacing = findViewById<EditText>(R.id.et_spacing)
        val lead = findViewById<EditText>(R.id.et_parking_lead)
        val modeGroup = findViewById<RadioGroup>(R.id.rg_alarm_mode)

        enabled.isChecked = s.reminderEnabled
        scanHours.setText(s.reminderScanHours.toString())
        daysBefore.setText(s.reminderDaysBefore.toString())
        // ROOT, not the default locale: this field is read back by parseTime(), and a locale with
        // non-ASCII digits would render a time this screen can no longer parse.
        remindTime.setText(String.format(Locale.ROOT, "%02d:%02d", s.reminderHour, s.reminderMinute))
        spacing.setText(s.reminderSpacingMinutes.toString())
        lead.setText(s.parkingLeadMinutes.toString())
        when (ParkingAlarmMode.of(s.parkingAlarmMode)) {
            ParkingAlarmMode.IN_APP -> findViewById<RadioButton>(R.id.rb_alarm_inapp).isChecked = true
            else -> findViewById<RadioButton>(R.id.rb_alarm_clock).isChecked = true
        }

        // Persist the backend on selection rather than on Save: the test buttons act on whatever is
        // selected, and testing one mode while the other is still stored would be a trap.
        modeGroup.setOnCheckedChangeListener { _, id ->
            val was = ParkingAlarm.mode(this)
            val now = if (id == R.id.rb_alarm_inapp) ParkingAlarmMode.IN_APP else ParkingAlarmMode.SYSTEM_CLOCK
            // Leaving the clock backend has to take its alarms with it. Once the mode changes,
            // nothing dismisses them again, and a stranded clock alarm rings for a session ariyipp
            // has stopped tracking - which device testing produced for real.
            if (was == ParkingAlarmMode.SYSTEM_CLOCK && now != was) ParkingAlarm.dismissAllClockAlarms(this)
            s.parkingAlarmMode = now.name
            if (now != was) Thread { ReminderScheduler.rebuildAll(applicationContext) }.start()
            refreshAlarmWarning()
        }

        findViewById<View>(R.id.btn_rulebook).setOnClickListener {
            startActivity(Intent(this, ReminderRulesActivity::class.java))
        }
        findViewById<View>(R.id.btn_calculate_now).setOnClickListener {
            if (!Settings.get(this).reminderEnabled) {
                Toast.makeText(this, R.string.rm_empty_disabled, Toast.LENGTH_LONG).show()
            } else {
                ReminderScanWorker.runNow(this, full = true)
                Toast.makeText(this, R.string.rm_scan_running, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btn_test_alarm).setOnClickListener {
            // Save the lead/mode first so the test uses what's on screen.
            s.parkingLeadMinutes = lead.text.toString().toIntOrNull() ?: s.parkingLeadMinutes
            val ok = ParkingAlarm.test(this, minutes = 2)
            Toast.makeText(this, if (ok) R.string.rm_test_sent else R.string.rm_test_failed, Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btn_cancel_test).setOnClickListener {
            ParkingAlarm.cancelTest(this)
            Toast.makeText(this, R.string.rm_cancel_test_sent, Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btn_grant_exact).setOnClickListener {
            ParkingAlarm.exactAlarmSettingsIntent(this)?.let { safeStart(it) }
        }
        findViewById<View>(R.id.btn_dnd_access).setOnClickListener { safeStart(ReminderNotifier.dndAccessIntent()) }
        findViewById<View>(R.id.btn_channel_parking).setOnClickListener {
            ReminderNotifier.ensureChannels(this)
            safeStart(ReminderNotifier.channelSettingsIntent(this, ReminderNotifier.CHANNEL_PARKING))
        }
        findViewById<View>(R.id.btn_channel_bills).setOnClickListener {
            ReminderNotifier.ensureChannels(this)
            safeStart(ReminderNotifier.channelSettingsIntent(this, ReminderNotifier.CHANNEL_BILLS))
        }

        // Everything on this screen is a preference, so it saves as it changes - no Save button to
        // hunt for, and no way to leave with edits silently discarded. Text fields commit when they
        // lose focus and again in onPause, since a field still under the cursor has no other moment
        // that counts as "done".
        enabled.setOnCheckedChangeListener { _, _ -> commit() }
        for (field in listOf(scanHours, daysBefore, remindTime, spacing, lead)) {
            field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        }

        refreshAlarmWarning()
        refreshDndState()
    }

    override fun onResume() {
        super.onResume()
        // Both grants happen in system settings, so re-check whatever changed while we were away.
        refreshAlarmWarning()
        refreshDndState()
    }

    /**
     * Says where the user is in the two-step Do Not Disturb setup, because neither step is
     * something the app can do for them and a bare button gave no clue which one was outstanding.
     */
    private fun refreshDndState() {
        val tv = findViewById<TextView>(R.id.tv_dnd_state) ?: return
        val bypassing = ReminderNotifier.parkingBypassesDnd(this)
        val access = ReminderNotifier.hasDndAccess(this)
        val (msg, attr) = when {
            bypassing -> getString(R.string.rm_dnd_done) to androidx.appcompat.R.attr.colorPrimary
            access -> getString(R.string.rm_dnd_step2) to com.google.android.material.R.attr.colorOnSurfaceVariant
            else -> getString(R.string.rm_dnd_step1) to com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        tv.text = msg
        tv.setTextColor(MaterialColors.getColor(tv, attr))
        // Visible until the bypass is actually on, because the button is what turns it on - both
        // before the grant (it asks for it) and after (it applies it). Keying this off `access`
        // instead hid the button at the exact moment the text said to tap it.
        findViewById<View>(R.id.btn_dnd_access).visibility = if (bypassing) View.GONE else View.VISIBLE
    }

    /**
     * Says plainly what will and won't work, and offers the fix. A parking alarm that silently
     * never fires is the worst possible failure for this feature, so every known limitation is
     * stated here rather than left to be discovered at the meter.
     *
     * Three different things can be wrong, and they are not equally bad, so they are ranked: the
     * backend being unusable outright (red, with the grant button), then the clock app's background
     * restriction, then full-screen alarms being turned off.
     */
    private fun refreshAlarmWarning() {
        val warning = findViewById<TextView>(R.id.tv_alarm_warning) ?: return
        val grant = findViewById<View>(R.id.btn_grant_exact)
        val mode = ParkingAlarm.mode(this)
        val blocking = ParkingAlarm.unavailableReason(this)

        val (text, isError) = when {
            blocking != null -> blocking to true
            mode == ParkingAlarmMode.SYSTEM_CLOCK -> getString(R.string.rm_alarm_clock_caveat) to false
            !ReminderNotifier.canRingFullScreen(this) -> getString(R.string.rm_fsi_missing) to false
            else -> null to false
        }

        if (text == null) {
            warning.visibility = View.GONE
        } else {
            warning.visibility = View.VISIBLE
            warning.text = text
            warning.setTextColor(
                MaterialColors.getColor(
                    warning,
                    if (isError) androidx.appcompat.R.attr.colorError
                    else com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            )
        }
        // The grant only helps the in-app backend, and only when it is actually missing.
        grant.visibility =
            if (mode == ParkingAlarmMode.IN_APP && !ParkingAlarm.canScheduleExact(this)) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        commit()
    }

    /**
     * Writes the screen into settings and re-applies anything that depends on it. Idempotent and
     * cheap enough to run on every change: the scheduler work is WorkManager bookkeeping plus a
     * rebuild from the database, both of which no-op when nothing actually moved.
     */
    private fun commit() {
        val s = Settings.get(this)
        val wasEnabled = s.reminderEnabled
        s.reminderEnabled = findViewById<MaterialSwitch>(R.id.sw_reminder_enabled).isChecked
        s.reminderScanHours = text(R.id.et_scan_hours)?.toIntOrNull() ?: s.reminderScanHours
        s.reminderDaysBefore = text(R.id.et_days_before)?.toIntOrNull() ?: s.reminderDaysBefore
        parseTime(text(R.id.et_remind_time).orEmpty())?.let { (h, m) ->
            s.reminderHour = h
            s.reminderMinute = m
        }
        s.reminderSpacingMinutes = text(R.id.et_spacing)?.toIntOrNull() ?: s.reminderSpacingMinutes
        s.parkingLeadMinutes = text(R.id.et_parking_lead)?.toIntOrNull() ?: s.parkingLeadMinutes

        ReminderScanWorker.applyFromSettings(this)
        if (s.reminderEnabled) {
            ReminderNotifier.ensureChannels(this)
            Thread { ReminderScheduler.rebuildAll(applicationContext) }.start()
        } else {
            // Turning the module off has to withdraw what it scheduled, or a reminder set last week
            // still fires for a feature the user has switched off.
            ParkingAlarm.dismissAllClockAlarms(this)
            Thread { ReminderScheduler.cancelAll(applicationContext) }.start()
        }
        // The tab appears and disappears with the module, and the nav bar is built in onCreate.
        if (wasEnabled != s.reminderEnabled) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private fun text(id: Int): String? = findViewById<EditText>(id)?.text?.toString()?.trim()

    private fun parseTime(raw: String): Pair<Int, Int>? {
        val parts = raw.trim().split(":", ".")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.rm_test_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
