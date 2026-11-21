package com.noti.logger

import android.app.Application
import android.content.Context
import com.noti.logger.config.Settings
import com.noti.logger.push.FirebaseInit
import com.noti.logger.push.Heartbeat
import com.noti.logger.util.Theming
import com.noti.logger.work.HeartbeatWorker
import com.noti.logger.work.UploadScheduler
import com.noti.sender.SmsSyncWorker
import com.noti.shared.Role

class NotiApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply the user's light/dark/system choice. Color (Default blue vs Material You) is applied
        // per-activity via Theming.applyDynamicColorIfEnabled().
        Theming.applyNightMode(this)

        // Cheap, and needed before any reminder work runs: the clock-app alarm route can only be
        // attempted while an activity is on screen (see AppForeground).
        com.noti.logger.reminder.AppForeground.register(this)

        // Tell the :sender library which role this phone plays, before any of its receivers,
        // services or workers can run in this process: they relay only while it's the companion.
        com.noti.sender.CompanionRole.check = { Settings.get(it).role == Role.COMPANION }
        // Screenshot protection reads the store of whichever role this phone plays.
        com.noti.sender.ui.ScreenGuard.policy = { ctx, kind ->
            if (Settings.get(ctx).role == Role.COMPANION) {
                kind == com.noti.sender.ui.ScreenGuard.Kind.KEYS &&
                    com.noti.sender.config.SenderSettings.get(ctx).protectKeyScreens
            } else when (kind) {
                com.noti.sender.ui.ScreenGuard.Kind.KEYS -> Settings.get(ctx).protectKeyScreens
                com.noti.sender.ui.ScreenGuard.Kind.MESSAGES -> Settings.get(ctx).protectMessages
                com.noti.sender.ui.ScreenGuard.Kind.NONE -> false
            }
        }

        // Everything below reads EncryptedSharedPreferences (Tink init + an AES decrypt per field),
        // which would otherwise sit on the critical path to the first frame. Every action it takes is
        // WorkManager bookkeeping - idempotent and thread-safe - so run it on a background thread.
        val appContext = applicationContext
        Thread {
            try {
                initialize(appContext)
            } catch (_: Exception) {
                // Scheduling failures must never crash startup; the next process start re-runs this.
            }
        }.apply { name = "noti-app-init"; start() }
    }

    private fun initialize(appContext: Context) {
        // BYO-FCM: init Firebase from the user-imported google-services.json, if any. No-op (and no
        // crash) before it's imported; FCM-touching calls elsewhere guard on this too.
        FirebaseInit.ensureInitialized(appContext)

        when (Settings.get(appContext).role) {
            Role.COMPANION -> {
                // Main's own background work, in case this phone was Main before a switch.
                UploadScheduler.cancelPeriodic(appContext)
                com.noti.logger.work.ReminderScanWorker.cancelPeriodic(appContext)
                // Keep the relay backstop scheduled and the high-water mark set from any process start.
                SmsSyncWorker.baselineIfNeeded(appContext)
                SmsSyncWorker.schedulePeriodic(appContext)
                // Re-announce our endpoint so Main always has a live token (self-heals a stale one,
                // e.g. an in-place hub update that kept the old ariy token). Only fires when the token
                // actually changed since the last successful announce - a plain process restart
                // shouldn't cost a network round-trip every time. No-op if not paired.
                com.noti.sender.TokenAnnounceWorker.enqueueIfNeeded(appContext)
                // Device-status alerts must not depend on the keep-alive service being enabled -
                // that's a user-facing toggle, and someone turning it off shouldn't silently lose
                // the low-battery warning. The live monitor still rides with the service (it needs
                // a warm process to get callbacks at all); this schedules the periodic backstop
                // from any process start, and starts the monitor for the process we're already in.
                com.noti.sender.StatusWatchWorker.schedule(appContext)
                com.noti.sender.status.DeviceMonitor.start(appContext)
            }
            // MAIN (or not-yet-chosen): the hub's notification-upload backstop, and the Reminder
            // module. Reminders are MAIN-only - the rulebook reads captured notifications and the
            // relayed chat history, both of which only exist on this side of the pair.
            else -> {
                // Leftovers from a switch away from Companion: WorkManager jobs survive the restart
                // and the manifest receivers still wake, so stop the relay explicitly.
                com.noti.sender.CompanionRole.stopAll(appContext)
                // Re-announce our endpoint so the companion always has a live hub token. Ask
                // Firebase for the current one rather than trusting the stored copy: a wipe can
                // rotate it without onNewToken ever reaching us. Only sends when it changed.
                if (Settings.get(appContext).peerPaired() && FirebaseInit.ensureInitialized(appContext)) {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { com.noti.logger.work.HubTokenAnnounceWorker.enqueueIfNeeded(appContext, it) }
                }
                UploadScheduler.applyFromSettings(appContext)
                if (Settings.get(appContext).reminderEnabled) {
                    com.noti.logger.reminder.ReminderNotifier.ensureChannels(appContext)
                    com.noti.logger.work.ReminderScanWorker.applyFromSettings(appContext)
                    // Re-arm from the database: an app update or a force-stop clears pending alarms
                    // just as a reboot does, and neither raises BOOT_COMPLETED.
                    com.noti.logger.reminder.ReminderScheduler.rebuildAll(appContext)
                } else {
                    com.noti.logger.work.ReminderScanWorker.cancelPeriodic(appContext)
                }
            }
        }

        // Liveness heartbeat (both roles). Only schedule the recurring worker when it's actually
        // enabled - a user who turned heartbeat off shouldn't have a 15-min worker firing to no-op.
        if (Settings.get(appContext).role != null && Heartbeat.enabled(appContext)) {
            Heartbeat.baselineIfNeeded(appContext)
            HeartbeatWorker.schedulePeriodic(appContext)
        } else {
            HeartbeatWorker.cancel(appContext)
        }
    }
}
