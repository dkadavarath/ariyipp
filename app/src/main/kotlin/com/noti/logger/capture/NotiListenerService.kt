package com.noti.logger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.noti.logger.config.Settings
import com.noti.logger.config.TriggerMode
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.NotificationEntity
import com.noti.logger.redact.RawNotification
import com.noti.logger.redact.RedactedResult
import com.noti.logger.redact.RedactionRules
import com.noti.logger.reminder.ReminderTrigger
import com.noti.logger.util.AppLabelCache
import com.noti.logger.util.contentHash
import com.noti.logger.work.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NotiListenerService : NotificationListenerService() {

    private companion object {
        /** Per-field cap on captured notification text - generous against any real notification,
         *  bounds worst-case row/storage size regardless of source. */
        const val MAX_FIELD_CHARS = 4000

        const val PER_PACKAGE_WINDOW_MS = 60 * 60 * 1000L // 1 hour
        /** Generous for even a very chatty legitimate app; bounds a runaway or hostile source. */
        const val MAX_PER_PACKAGE_PER_WINDOW = 300

        /** Hard ceiling on un-uploaded rows regardless of how many sources contribute - if this
         *  many have piled up (e.g. no webhook configured), something is already wrong and further
         *  capture should stop until the backlog clears via upload or the retention purge. */
        const val MAX_PENDING_ROWS = 10_000

        /** Local-only mode (no webhook): how often the age purge runs, and how many of the oldest
         *  rows go when the cap is still hit. */
        const val LOCAL_PURGE_INTERVAL_MS = 60 * 60 * 1000L
        const val LOCAL_TRIM_ROWS = 1_000
    }

    /** The last kept signature of each ongoing notification (touched only on [serialIo]); bounded,
     *  oldest out, since keys of dismissed notifications are never told to us here. */
    private val ongoingSeen = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    /** Last local-only age purge (in-memory; touched only on [serialIo]). */
    private var lastLocalPurgeMs = 0L

    // Single-threaded so the dedup check + insert per notification can't race for rapid bursts.
    // Declared BEFORE `scope` so it is initialized when the first newScope() runs.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serialIo = Dispatchers.IO.limitedParallelism(1)

    // Recreated on every (re)connect: the system routinely disconnects/reconnects
    // listeners (e.g. during Doze), and a once-cancelled scope would silently drop
    // all subsequent notifications. @Volatile so the callback thread sees fresh refs.
    @Volatile
    private var scope = newScope()

    private val labelCache: AppLabelCache by lazy { AppLabelCache(applicationContext) }

    private fun newScope() = CoroutineScope(SupervisorJob() + serialIo)

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!scope.isActive) scope = newScope()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        scope.cancel()
    }

    /** A dismissed notification is forgotten: the app may post the same key again for a new
     *  session that happens to read the same, and that must be stored. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        scope.launch { ongoingSeen.remove(key) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val flags = sbn.notification.flags
        // Skip group summaries - redundant with their children.
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        // Ongoing events are mostly noise (media, timers, the keep-alive of other apps) and are
        // skipped - except from an app a reminder rule reads, decided off this thread below:
        // a parking app shows the running session as exactly such a notification.
        val ongoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
        // Skip notifications from our own app
        if (sbn.packageName == applicationContext.packageName) return

        // Read all fields on the callback thread (cheap Bundle reads only)
        val packageName = sbn.packageName
        val postTime = sbn.postTime
        val sbnKey = sbn.key
        val category = sbn.notification.category
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        scope.launch {
            // An ongoing one is kept only for what a rule reads out of it, and only when that
            // changed: a countdown that re-posts every minute would otherwise be a row, a scan and
            // an upload per tick. Recorded once it's stored, so a dropped update isn't "seen".
            var ongoingSig: String? = null
            if (ongoing) {
                if (!ReminderTrigger.watchesApp(applicationContext, packageName)) return@launch
                ongoingSig = ReminderTrigger.ongoingSignature(
                    applicationContext, packageName,
                    com.noti.logger.reminder.ReminderExtractor.notificationText(title, text, bigText, subText),
                ) ?: return@launch
                if (ongoingSeen[sbnKey] == ongoingSig) return@launch
            }
            val settings = Settings.get(applicationContext)
            val raw = RawNotification(
                packageName = packageName,
                title = title,
                text = text,
                bigText = bigText,
                subText = subText,
                category = category
            )

            val result = RedactionRules(settings.redactionConfig()).apply(raw)
            if (result is RedactedResult.Dropped) return@launch

            val kept = result as RedactedResult.Kept

            val dao = NotiDatabase.get(applicationContext).notificationDao()

            // Content signature from RAW fields (works even in metadata-only mode).
            val hash = contentHash(packageName, title, text, bigText, subText)

            // Duplicate suppression: drop identical content already captured within the window.
            val windowSeconds = settings.dedupeWindowSeconds
            val now = System.currentTimeMillis()
            if (windowSeconds > 0 &&
                dao.countRecentByHash(hash, now - windowSeconds.toLong() * 1000L) > 0
            ) {
                return@launch
            }

            // Storage-exhaustion backstop: any installed app can post local notifications with no
            // special permission, and with the default empty capture allowlist every package is
            // captured. Unique content per post defeats the hash dedup above, so a noisy or hostile
            // source could otherwise grow the table without bound. Per-package caps a single source;
            // the total cap is a hard ceiling regardless of how many sources contribute.
            if (dao.countRecentByPackage(packageName, now - PER_PACKAGE_WINDOW_MS) >= MAX_PER_PACKAGE_PER_WINDOW) {
                return@launch
            }
            if (settings.webhookUrl.isBlank()) {
                // Local-only: nothing is ever uploaded, so nothing is ever marked uploaded and the
                // upload worker's purge never touches these rows. Without this the table hit the
                // cap and capture (and notification-based Reminder rules) stopped for good. Prune
                // by age instead - at least a day, so the Reminder scan still sees recent rows even
                // with retention 0 - and trim the oldest if a flood still reaches the cap.
                if (now - lastLocalPurgeMs >= LOCAL_PURGE_INTERVAL_MS) {
                    lastLocalPurgeMs = now
                    val days = settings.retentionDays.coerceAtLeast(1)
                    try { dao.purgeOlderThan(now - days * 86_400_000L) } catch (_: Exception) {}
                }
                if (dao.pendingCount() >= MAX_PENDING_ROWS) dao.trimOldest(LOCAL_TRIM_ROWS)
            } else if (dao.pendingCount() >= MAX_PENDING_ROWS) {
                return@launch
            }

            // uid: stable concat so the unique DB index deduplicates re-posts of the same sbnKey
            val uid = "${settings.deviceId}|$sbnKey|$postTime"

            val entity = NotificationEntity(
                uid = uid,
                packageName = packageName,
                appLabel = labelCache.label(packageName),
                postTime = postTime,
                title = kept.title?.take(MAX_FIELD_CHARS),
                text = kept.text?.take(MAX_FIELD_CHARS),
                bigText = kept.bigText?.take(MAX_FIELD_CHARS),
                subText = kept.subText?.take(MAX_FIELD_CHARS),
                category = category,
                sbnKey = sbnKey,
                contentHash = hash,
                createdAt = now
            )

            val rowId = dao.insert(entity)
            if (ongoingSig != null && rowId != -1L) ongoingSeen[sbnKey] = ongoingSig

            // A rule may be about this app: run the rulebook now rather than at the next scan.
            if (rowId != -1L) {
                try { ReminderTrigger.onNotification(applicationContext, packageName) } catch (_: Exception) {}
            }

            if (rowId != -1L &&
                settings.triggerMode == TriggerMode.THRESHOLD &&
                dao.pendingCount() >= settings.thresholdCount
            ) {
                UploadScheduler.enqueueOneShot(applicationContext)
            }
        }
    }
}
