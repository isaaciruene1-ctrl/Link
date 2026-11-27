package com.example.continuity

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.LinkApplication
import com.example.data.local.SyncedNotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

class LinkNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val app = application as? LinkApplication ?: return
        val container = app.container
        scope.launch {
            val prefs = container.preferencesRepository.preferencesFlow.first()
            if (!prefs.notificationSyncOptIn) return@launch

            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (title.isBlank() && text.isBlank()) return@launch

            val appName = runCatching {
                val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
                packageManager.getApplicationLabel(ai).toString()
            }.getOrDefault(sbn.packageName)

            val isSensitive = prefs.hideSensitiveNotifications &&
                (sbn.notification.visibility == Notification.VISIBILITY_PRIVATE ||
                    sbn.notification.visibility == Notification.VISIBILITY_SECRET ||
                    SensitiveDataDetector.isSensitiveText("$title $text"))

            val hasReply = sbn.notification.actions?.any { action ->
                action.remoteInputs?.isNotEmpty() == true
            } == true

            val entity = SyncedNotificationEntity(
                notificationKey = sbn.key,
                sourceDeviceId = container.identityManager.getOrCreateIdentity().deviceId,
                sourceDeviceName = prefs.deviceDisplayName,
                packageName = sbn.packageName,
                appName = appName,
                title = title.ifBlank { appName },
                bodyPreview = if (isSensitive) "Sensitive notification content hidden" else text.take(300),
                hasReplyAction = hasReply,
                isSensitiveHidden = isSensitive,
                postedAt = sbn.postTime
            )
            container.database.syncedNotificationDao().upsert(entity)

            container.connectivityEngine.broadcastControlMessage(
                "NOTIFICATION_POST",
                JSONObject().apply {
                    put("key", sbn.key)
                    put("packageName", sbn.packageName)
                    put("appName", appName)
                    put("title", entity.title)
                    put("body", entity.bodyPreview)
                    put("hasReply", hasReply)
                    put("sensitive", isSensitive)
                    put("postedAt", sbn.postTime)
                }
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val app = application as? LinkApplication ?: return
        scope.launch {
            app.container.database.syncedNotificationDao().markDismissed(sbn.key)
            app.container.connectivityEngine.broadcastControlMessage(
                "NOTIFICATION_DISMISS",
                JSONObject().put("key", sbn.key)
            )
        }
    }

    private fun syncActiveNotifications() {
        runCatching {
            activeNotifications?.forEach { onNotificationPosted(it) }
        }
    }

    companion object {
        @Volatile
        private var instance: LinkNotificationListenerService? = null

        fun dismissRemoteOrLocal(key: String) {
            runCatching { instance?.cancelNotification(key) }
        }
    }
}
