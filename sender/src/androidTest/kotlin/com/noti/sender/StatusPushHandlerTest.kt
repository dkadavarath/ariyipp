package com.noti.sender

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.sender.config.SenderSettings
import com.noti.sender.status.StatusPushHandler
import com.noti.shared.DeviceStatusPolicy
import com.noti.shared.MessageCrypto
import com.noti.shared.Wire
import com.noti.shared.WireMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusPushHandlerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = MessageCrypto.generateKeyBase64()

    @Before fun setup() {
        val s = SenderSettings.get(ctx)
        s.relayKey = key
        s.statusSharingEnabled = true
        s.lastStatusConfigVersion = -1L
        s.statusBatteryPct = DeviceStatusPolicy.DEFAULT_BATTERY_PCT
        s.statusWifiLossMinutes = DeviceStatusPolicy.DEFAULT_WIFI_LOSS_MINUTES
        s.statusPollSeconds = DeviceStatusPolicy.DEFAULT_POLL_SECONDS
        s.batteryAlertArmed = true
    }

    @After fun teardown() {
        val s = SenderSettings.get(ctx)
        s.relayKey = ""
        s.statusSharingEnabled = true
        s.lastStatusConfigVersion = -1L
        s.statusBatteryPct = DeviceStatusPolicy.DEFAULT_BATTERY_PCT
        s.statusWifiLossMinutes = DeviceStatusPolicy.DEFAULT_WIFI_LOSS_MINUTES
        s.statusPollSeconds = DeviceStatusPolicy.DEFAULT_POLL_SECONDS
    }

    private fun payload(msg: WireMessage, encKey: String = key): Map<String, String> =
        mapOf(StatusPushHandler.PAYLOAD_KEY to MessageCrypto.encrypt(Wire.encodeFor(msg, Wire.Direction.MAIN_TO_COMPANION), encKey))

    @Test fun parses_a_status_request() {
        val req = WireMessage.StatusRequest(forSeconds = 60)
        assertEquals(req, StatusPushHandler.parse(ctx, payload(req)))
    }

    @Test fun parses_a_cancel_request() {
        val req = WireMessage.StatusRequest(forSeconds = 0)
        assertEquals(req, StatusPushHandler.parse(ctx, payload(req)))
    }

    @Test fun parses_a_valid_config() {
        val cfg = WireMessage.StatusConfig(batteryPct = 15, wifiLossMinutes = 30, configVersion = 1L)
        assertEquals(cfg, StatusPushHandler.parse(ctx, payload(cfg)))
    }

    @Test fun master_switch_off_returns_null() {
        SenderSettings.get(ctx).statusSharingEnabled = false
        assertNull(StatusPushHandler.parse(ctx, payload(WireMessage.StatusRequest(forSeconds = 60))))
        assertNull(StatusPushHandler.parse(ctx, payload(WireMessage.StatusConfig(configVersion = 1L))))
    }

    @Test fun wrong_key_returns_null() {
        assertNull(
            StatusPushHandler.parse(
                ctx, payload(WireMessage.StatusRequest(forSeconds = 60), MessageCrypto.generateKeyBase64()),
            ),
        )
    }

    @Test fun a_relay_is_not_a_status_message() {
        assertNull(StatusPushHandler.parse(ctx, payload(WireMessage.Relay(title = "x", body = "y"))))
    }

    @Test fun stale_or_replayed_configVersion_returns_null() {
        SenderSettings.get(ctx).lastStatusConfigVersion = 5L
        assertNull(StatusPushHandler.parse(ctx, payload(WireMessage.StatusConfig(configVersion = 5L))))
        assertNull(StatusPushHandler.parse(ctx, payload(WireMessage.StatusConfig(configVersion = 4L))))
        assertEquals(
            WireMessage.StatusConfig(configVersion = 6L),
            StatusPushHandler.parse(ctx, payload(WireMessage.StatusConfig(configVersion = 6L))),
        )
    }

    @Test fun a_request_is_never_blocked_by_the_config_version_guard() {
        // The guard is for configs only - a request carries no version and must always be honoured.
        SenderSettings.get(ctx).lastStatusConfigVersion = Long.MAX_VALUE
        assertEquals(
            WireMessage.StatusRequest(forSeconds = 60),
            StatusPushHandler.parse(ctx, payload(WireMessage.StatusRequest(forSeconds = 60))),
        )
    }

    @Test fun apply_stores_the_thresholds_and_the_version() {
        val cfg = WireMessage.StatusConfig(
            alertsEnabled = false, batteryPct = 35, wifiLossMinutes = 45, pollSeconds = 30, configVersion = 7L,
        )
        StatusPushHandler.apply(ctx, cfg)
        val s = SenderSettings.get(ctx)
        assertEquals(false, s.statusAlertsEnabled)
        assertEquals(35, s.statusBatteryPct)
        assertEquals(45, s.statusWifiLossMinutes)
        assertEquals(30, s.statusPollSeconds)
        assertEquals(7L, s.lastStatusConfigVersion)
        s.statusAlertsEnabled = true
    }

    @Test fun apply_clamps_an_out_of_range_config() {
        // A hostile or buggy config must not be able to ask for a 1-second poll (battery drain) or
        // a threshold outside what the UI can express.
        StatusPushHandler.apply(
            ctx,
            WireMessage.StatusConfig(batteryPct = 999, wifiLossMinutes = 0, pollSeconds = 1, configVersion = 8L),
        )
        val s = SenderSettings.get(ctx)
        assertEquals(DeviceStatusPolicy.MAX_BATTERY_PCT, s.statusBatteryPct)
        assertEquals(DeviceStatusPolicy.MIN_WIFI_LOSS_MINUTES, s.statusWifiLossMinutes)
        assertEquals(DeviceStatusPolicy.MIN_POLL_SECONDS, s.statusPollSeconds)
    }

    @Test fun raising_the_threshold_re_arms_the_battery_alert() {
        // Otherwise a user who raises the threshold because the battery is already low would hear
        // nothing until the next full discharge cycle.
        val s = SenderSettings.get(ctx)
        s.batteryAlertArmed = false
        StatusPushHandler.apply(ctx, WireMessage.StatusConfig(batteryPct = 40, configVersion = 9L))
        assertTrue(s.batteryAlertArmed)
    }
}
