package com.noti.logger

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.config.Settings
import com.noti.logger.push.PushMessageHandler
import com.noti.logger.ui.DiagnosticsActivity
import com.noti.shared.MessageCrypto
import com.noti.shared.Role
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The companion status card renders as icons, and this is the only way to see it work: the card is
 * gated on being paired, so it never appears on a single test device unless the pairing fields are
 * seeded. Without a test the card could only be checked by pairing two real phones.
 *
 * It lives on the Diagnostics screen - it is a diagnostic, and putting it there ties the polling
 * window to that screen being open rather than to a tab being selected.
 *
 * What matters here is that the gauges are driven from the report rather than being decorative:
 * `setImageLevel` on a clip-backed drawable is what makes the battery and signal icons show a real
 * level, and a wrong scale would look plausible while being meaningless.
 */
@RunWith(AndroidJUnit4::class)
class CompanionStatusCardTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = MessageCrypto.generateKeyBase64()
    private var savedRole: Role? = null

    @Before fun seedPairedWithAReport() {
        val s = Settings.get(ctx)
        savedRole = s.role
        s.role = Role.MAIN
        // peerPaired() needs all three; the values only have to be non-blank for the card's gate.
        s.relayKey = key
        s.sndiFcmToken = "test-companion-token"
        s.serviceAccountJson = "{}"
        s.companionStatusEnabled = true

        val report = WireMessage.StatusReport(
            atMs = System.currentTimeMillis(),
            batteryPct = 42, charging = true, powerSave = true,
            signalLevel = 3, networkType = "LTE", roaming = true,
            wifi = true, wifiLevel = 3, online = true, vpn = true,
        )
        PushMessageHandler.handle(ctx, mapOf(PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(Wire.encodeFor(report, Wire.Direction.COMPANION_TO_MAIN), key)))
    }

    @After fun clear() {
        val s = Settings.get(ctx)
        s.relayKey = ""
        s.sndiFcmToken = ""
        s.serviceAccountJson = ""
        s.lastCompanionStatus = ""
        s.lastCompanionStatusAtMs = 0L
        s.role = savedRole
    }

    @Test fun the_card_shows_icon_tiles_driven_by_the_report() {
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val card = activity.findViewById<View>(R.id.card_companion_status)
                assertEquals("card hidden despite being paired", View.VISIBLE, card.visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.row_cs_tiles).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.tv_cs_readings).visibility)

                assertEquals("42%", activity.findViewById<TextView>(R.id.tv_cs_battery).text.toString())
                assertEquals("LTE", activity.findViewById<TextView>(R.id.tv_cs_signal).text.toString())
                assertEquals("Wi-Fi", activity.findViewById<TextView>(R.id.tv_cs_network).text.toString())

                // A fresh report has missed nothing, so no mark.
                assertEquals(View.GONE, activity.findViewById<View>(R.id.iv_cs_warning).visibility)

                // The gauges must be at the right level, not merely present: 42% of 10000, and
                // 3 of 4 bars. This is what separates a real gauge from a static glyph.
                assertEquals(4200, activity.findViewById<ImageView>(R.id.iv_cs_battery).drawable.level)
                assertEquals(7500, activity.findViewById<ImageView>(R.id.iv_cs_signal).drawable.level)

                // Charging bolt and VPN tile appear only when those are true.
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.iv_cs_charging).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tile_cs_vpn).visibility)

                // Occasional conditions collapse into one line rather than three permanent rows.
                val flags = activity.findViewById<TextView>(R.id.tv_cs_flags)
                assertEquals(View.VISIBLE, flags.visibility)
                assertEquals("Battery saver is on · Roaming", flags.text.toString())
            }
        }
    }

    @Test fun values_hide_when_labels_are_turned_off_but_the_gauges_stay() {
        // The compact mode: icons alone. The gauges still carry the level, which is the point -
        // hiding the numbers must not hide the information.
        Settings.get(ctx).companionStatusLabels = false
        try {
            ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.row_cs_tiles).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.tv_cs_battery).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.tv_cs_signal).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.tv_cs_network).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.tv_cs_flags).visibility)
                    assertEquals(4200, activity.findViewById<ImageView>(R.id.iv_cs_battery).drawable.level)
                }
            }
        } finally {
            Settings.get(ctx).companionStatusLabels = true
        }
    }

    @Test fun a_readout_that_missed_two_updates_is_marked() {
        // Backdate the arrival past two poll intervals: one miss would change nothing on screen,
        // two means the retry failed too.
        val s = Settings.get(ctx)
        s.lastCompanionStatusAtMs = System.currentTimeMillis() - (s.companionPollSeconds * 3_000L)
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // The last known values are still on screen - they are not blanked.
                assertEquals("42%", activity.findViewById<TextView>(R.id.tv_cs_battery).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.iv_cs_warning).visibility)
            }
        }
    }

    @Test fun an_unmeasured_reading_dims_its_gauge_instead_of_reading_as_zero() {
        // A companion without READ_PHONE_STATE reports signalLevel -1. A 0-level gauge would say
        // "no signal", which is a different and alarming claim, so it is dimmed and shown as a dash.
        val unknown = WireMessage.StatusReport(atMs = System.currentTimeMillis(), batteryPct = -1, signalLevel = -1, mobileData = true)
        PushMessageHandler.handle(ctx, mapOf(PushMessageHandler.PAYLOAD_KEY to MessageCrypto.encrypt(Wire.encodeFor(unknown, Wire.Direction.COMPANION_TO_MAIN), key)))

        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("—", activity.findViewById<TextView>(R.id.tv_cs_battery).text.toString())
                assertEquals("—", activity.findViewById<TextView>(R.id.tv_cs_signal).text.toString())
                assertEquals("Mobile data", activity.findViewById<TextView>(R.id.tv_cs_network).text.toString())
                assertEquals(70, activity.findViewById<ImageView>(R.id.iv_cs_battery).imageAlpha)
                assertEquals(70, activity.findViewById<ImageView>(R.id.iv_cs_signal).imageAlpha)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.tile_cs_vpn).visibility)
            }
        }
    }
}
