package com.noti.logger

import android.content.Context
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.ui.ComposeActivity
import com.noti.sender.CompanionRole
import com.noti.sender.SmsSyncWorker
import com.noti.sender.StatusWatchWorker
import com.noti.sender.config.SenderSettings
import com.noti.shared.MessageCrypto
import com.noti.shared.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayHardeningUiTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var savedRole: Role? = null
    private var saved = Triple("", "", "")

    @Before
    fun setup() {
        val s = Settings.get(ctx)
        savedRole = s.role
        saved = Triple(s.serviceAccountJson, s.sndiFcmToken, s.relayKey)
    }

    @After
    fun teardown() {
        val s = Settings.get(ctx)
        s.role = savedRole
        s.serviceAccountJson = saved.first; s.sndiFcmToken = saved.second; s.relayKey = saved.third
    }

    private fun state(name: String): WorkInfo.State? =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(name).get().lastOrNull()?.state

    @Test
    fun switching_to_main_stops_the_companion_relay() {
        val s = Settings.get(ctx)
        s.role = Role.COMPANION
        SenderSettings.get(ctx).statusSharingEnabled = true
        SmsSyncWorker.schedulePeriodic(ctx)
        StatusWatchWorker.schedule(ctx)
        assertTrue(CompanionRole.active(ctx))
        assertEquals(WorkInfo.State.ENQUEUED, state("sms-scan-periodic"))

        s.role = Role.MAIN
        assertFalse("the :sender library must see the new role", CompanionRole.active(ctx))
        CompanionRole.stopAll(ctx)

        assertEquals(WorkInfo.State.CANCELLED, state("sms-scan-periodic"))
        // The status backstop no longer has a job of its own (it rides on sms-scan-periodic).
        state("status-watch-periodic").let { assertTrue("$it", it == null || it == WorkInfo.State.CANCELLED) }
    }

    @Test
    fun double_tapping_send_in_compose_sends_once() {
        val s = Settings.get(ctx)
        s.role = Role.MAIN
        // Configured enough to try; the fake key makes the network send fail fast, which is fine:
        // what matters is how many messages the taps create.
        s.serviceAccountJson = "{}"; s.sndiFcmToken = "tok"; s.relayKey = MessageCrypto.generateKeyBase64()
        val dao = NotiDatabase.get(ctx).relayedMessageDao()
        val before = dao.allMessages().count { it.outgoing == 1 }

        ActivityScenario.launch(ComposeActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.findViewById<EditText>(R.id.et_to).setText("+15550001111")
                a.findViewById<EditText>(R.id.et_body).setText("double tap test")
                val send = a.findViewById<MaterialButton>(R.id.btn_send)
                send.performClick()
                send.performClick()
                assertFalse("button is disabled after the first tap", send.isEnabled)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(1500)
        }
        val created = dao.allMessages().filter { it.outgoing == 1 && it.body == "double tap test" }
        assertEquals(1, created.size)
        assertEquals(before + 1, dao.allMessages().count { it.outgoing == 1 })
        created.forEach { dao.deleteMessage(it.id) }
    }

    @Test
    fun the_status_backstop_rides_on_the_sms_sync_job() {
        Settings.get(ctx).role = Role.COMPANION
        SenderSettings.get(ctx).statusSharingEnabled = true
        StatusWatchWorker.schedule(ctx)
        assertEquals(WorkInfo.State.ENQUEUED, state("sms-scan-periodic"))
        val own = state("status-watch-periodic")
        assertTrue("no separate 15-minute status job: $own", own == null || own == WorkInfo.State.CANCELLED)
    }
}
