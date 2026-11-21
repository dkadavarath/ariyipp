package com.noti.logger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.reminder.FxRates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ActivityScenario
import com.google.android.material.textfield.TextInputLayout
import com.noti.logger.ui.ReminderSettingsActivity

/** The default currency follows the phone's region until the user picks one. */
@RunWith(AndroidJUnit4::class)
class DefaultCurrencyTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var saved = ""

    @Before fun save() { saved = Settings.get(ctx).defaultCurrency }
    @After fun restore() { Settings.get(ctx).defaultCurrency = saved; Thread.sleep(1000) }

    @Test fun unset_follows_the_region() {
        Settings.get(ctx).defaultCurrency = ""
        val regional = FxRates.regional(ctx)
        assertTrue("regional = $regional", regional.matches(Regex("[A-Z]{3}")))
        assertEquals(regional, FxRates.home(ctx))
    }

    @Test fun a_choice_overrides_the_region() {
        Settings.get(ctx).defaultCurrency = "EUR"
        assertEquals("EUR", FxRates.home(ctx))
    }

    @Test fun live_rates_are_fetched_for_the_chosen_currency() {
        Settings.get(ctx).defaultCurrency = "EUR"
        FxRates.onHomeChanged(ctx)
        val snap = FxRates.current(ctx, "EUR") ?: return // offline emulator: nothing to check
        assertEquals(1.0, snap.rates["EUR"]!!, 0.0)
        assertTrue(snap.rates.containsKey("AED"))
    }

    private fun helperOnScreen(): Pair<String, String> {
        var helper = ""; var field = ""
        ActivityScenario.launch(ReminderSettingsActivity::class.java).use { sc ->
            sc.onActivity { a ->
                helper = a.findViewById<TextInputLayout>(R.id.til_home_currency).helperText.toString()
                field = a.findViewById<android.widget.EditText>(R.id.et_home_currency).text.toString()
            }
        }
        return helper to field
    }

    @Test fun the_settings_field_says_what_automatic_resolves_to() {
        Settings.get(ctx).defaultCurrency = ""
        val (helper, field) = helperOnScreen()
        assertEquals("", field) // blank = automatic; the field doesn't claim a choice
        assertTrue(helper, helper.startsWith("Automatic: ${FxRates.regional(ctx)}"))
    }

    @Test fun the_settings_field_shows_a_chosen_currency() {
        Settings.get(ctx).defaultCurrency = "EUR"
        val (helper, field) = helperOnScreen()
        assertEquals("EUR", field)
        assertTrue(helper, helper.startsWith("Totals are shown in EUR"))
    }
}
