package com.example.continuity

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.connectivity.ConnectivityManagerEngine
import com.example.data.cloud.CloudEcosystemRepository
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.LinkPreferencesRepository
import com.example.data.local.SyncedNotificationDao
import com.example.data.local.SyncedNotificationEntity
import com.example.security.DeviceIdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class LocalHandoffBanner(
    val id: String,
    val sourceDeviceName: String,
    val title: String,
    val actionType: String,
    val payload: String
)

class SmartHandoffEngine(
    private val context: Context,
    private val connectivityEngine: ConnectivityManagerEngine,
    private val identityManager: DeviceIdentityManager,
    private val notificationDao: SyncedNotificationDao,
    private val activityLogDao: ActivityLogDao,
    private val preferencesRepository: LinkPreferencesRepository,
    private val cloudRepository: CloudEcosystemRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingHandoffs = MutableStateFlow<List<LocalHandoffBanner>>(emptyList())
    val incomingHandoffs: StateFlow<List<LocalHandoffBanner>> = _incomingHandoffs.asStateFlow()

    val notifications: Flow<List<SyncedNotificationEntity>> = notificationDao.observeActiveNotifications()

    init {
        scope.launch {
            connectivityEngine.controlEvents.collect { event ->
                when (event.type) {
                    "HANDOFF_PUSH" -> {
                        val banner = LocalHandoffBanner(
                            id = UUID.randomUUID().toString(),
                            sourceDeviceName = event.peerName,
                            title = event.payload.optString("title", "Continue from ${event.peerName}"),
                            actionType = event.payload.optString("actionType", "OPEN_URL"),
                            payload = event.payload.optString("payload", "")
                        )
                        if (banner.payload.isNotBlank()) {
                            _incomingHandoffs.value = listOf(banner) + _incomingHandoffs.value.take(9)
                        }
                    }
                    "NOTIFICATION_POST" -> {
                        val key = event.payload.optString("key").ifBlank { return@collect }
                        notificationDao.upsert(
                            SyncedNotificationEntity(
                                notificationKey = "${event.peerDeviceId}:$key",
                                sourceDeviceId = event.peerDeviceId,
                                sourceDeviceName = event.peerName,
                                packageName = event.payload.optString("packageName", ""),
                                appName = event.payload.optString("appName", "App"),
                                title = event.payload.optString("title", ""),
                                bodyPreview = event.payload.optString("body", ""),
                                hasReplyAction = event.payload.optBoolean("hasReply", false),
                                isSensitiveHidden = event.payload.optBoolean("sensitive", false),
                                postedAt = event.payload.optLong("postedAt", System.currentTimeMillis())
                            )
                        )
                    }
                    "NOTIFICATION_DISMISS" -> {
                        val key = event.payload.optString("key").ifBlank { return@collect }
                        notificationDao.markDismissed("${event.peerDeviceId}:$key")
                    }
                }
            }
        }
    }

    fun sendHandoff(
        targetDeviceId: String?,
        actionType: String,
        title: String,
        payload: String
    ) {
        scope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            val localId = identityManager.getOrCreateIdentity().deviceId
            val cleanPayload = payload.trim().take(2000)
            if (cleanPayload.isBlank()) return@launch

            val lanSent = if (targetDeviceId != null) {
                connectivityEngine.sendControlMessage(
                    targetDeviceId,
                    "HANDOFF_PUSH",
                    JSONObject().apply {
                        put("actionType", actionType)
                        put("title", title)
                        put("payload", cleanPayload)
                    }
                )
            } else {
                connectivityEngine.broadcastControlMessage(
                    "HANDOFF_PUSH",
                    JSONObject().apply {
                        put("actionType", actionType)
                        put("title", title)
                        put("payload", cleanPayload)
                    }
                ) > 0
            }

            if (prefs.cloudWorkspaceSyncOptIn) {
                cloudRepository.publishHandoff(
                    sourceDeviceId = localId,
                    sourceDeviceName = prefs.deviceDisplayName,
                    targetDeviceId = targetDeviceId ?: "ALL",
                    actionType = actionType,
                    title = title,
                    payload = cleanPayload
                )
            }

            activityLogDao.insert(
                ActivityLogEntity(
                    logId = UUID.randomUUID().toString(),
                    category = "HANDOFF",
                    deviceId = targetDeviceId,
                    summary = "Dispatched handoff '$title' (${if (lanSent) "LAN" else "Cloud"})"
                )
            )
        }
    }

    fun executeHandoffPayload(actionType: String, payload: String): Boolean {
        return runCatching {
            when (actionType) {
                "OPEN_URL" -> {
                    val uri = Uri.parse(payload)
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }
                else -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, payload)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Open LINK Handoff").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                    true
                }
            }
        }.getOrDefault(false)
    }

    fun dismissLocalBanner(id: String) {
        _incomingHandoffs.value = _incomingHandoffs.value.filterNot { it.id == id }
    }

    fun markNotificationRead(key: String) {
        scope.launch { notificationDao.markRead(key) }
    }

    fun markAllNotificationsRead() {
        scope.launch { notificationDao.markAllRead() }
    }

    fun dismissNotification(notification: SyncedNotificationEntity) {
        scope.launch {
            notificationDao.markDismissed(notification.notificationKey)
            val localId = identityManager.getOrCreateIdentity().deviceId
            if (notification.sourceDeviceId == localId) {
                LinkNotificationListenerService.dismissRemoteOrLocal(notification.notificationKey)
            } else {
                val rawKey = notification.notificationKey.substringAfter(":", notification.notificationKey)
                connectivityEngine.sendControlMessage(
                    notification.sourceDeviceId,
                    "NOTIFICATION_DISMISS",
                    JSONObject().put("key", rawKey)
                )
            }
        }
    }
}
