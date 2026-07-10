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
import com.noti.logger.util.AppLabelCache
import com.noti.logger.work.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NotiListenerService : NotificationListenerService() {

    // Recreated on every (re)connect: the system routinely disconnects/reconnects
    // listeners (e.g. during Doze), and a once-cancelled scope would silently drop
    // all subsequent notifications. @Volatile so the callback thread sees fresh refs.
    @Volatile
    private var scope = newScope()

    private val labelCache: AppLabelCache by lazy { AppLabelCache(applicationContext) }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val flags = sbn.notification.flags
        // Skip ongoing events and group summaries — transient/redundant noise
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
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

            // uid: stable concat so the unique DB index deduplicates re-posts of the same sbnKey
            val uid = "${settings.deviceId}|$sbnKey|$postTime"

            val entity = NotificationEntity(
                uid = uid,
                packageName = packageName,
                appLabel = labelCache.label(packageName),
                postTime = postTime,
                title = kept.title,
                text = kept.text,
                bigText = kept.bigText,
                subText = kept.subText,
                category = category,
                sbnKey = sbnKey,
                createdAt = System.currentTimeMillis()
            )

            val dao = NotiDatabase.get(applicationContext).notificationDao()
            val rowId = dao.insert(entity)

            if (rowId != -1L &&
                settings.triggerMode == TriggerMode.THRESHOLD &&
                dao.pendingCount() >= settings.thresholdCount
            ) {
                UploadScheduler.enqueueOneShot(applicationContext)
            }
        }
    }
}
