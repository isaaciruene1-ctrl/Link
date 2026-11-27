package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trusted_devices")
data class TrustedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val manufacturer: String,
    val model: String,
    val deviceType: String,
    val publicKeyBase64: String,
    val fingerprintShort: String,
    val groupsCsv: String = "My Devices",
    val allowClipboardSync: Boolean = false,
    val allowNotificationSync: Boolean = true,
    val allowFileAutoAccept: Boolean = false,
    val allowRemoteControl: Boolean = false,
    val allowScreenShare: Boolean = false,
    val lastHost: String? = null,
    val lastPort: Int = 0,
    val lastConnectedAt: Long = System.currentTimeMillis(),
    val pairedAt: Long = System.currentTimeMillis(),
    val isTrusted: Boolean = true,
    val relaySecretBase64: String = ""
)

@Entity(tableName = "transfer_records")
data class TransferRecordEntity(
    @PrimaryKey val transferId: String,
    val peerDeviceId: String,
    val peerDeviceName: String,
    val direction: String, // OUTGOING or INCOMING
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val sha256Checksum: String = "",
    val checksumVerified: Boolean = false,
    val status: String, // QUEUED, IN_PROGRESS, PAUSED, COMPLETED, FAILED, CANCELLED
    val localUri: String,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null
)

@Entity(tableName = "clipboard_entries")
data class ClipboardEntryEntity(
    @PrimaryKey val entryId: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val contentPreview: String,
    val fullText: String,
    val contentType: String, // TEXT or URL
    val isSensitiveRedacted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 86_400_000L
)

@Entity(tableName = "synced_notifications")
data class SyncedNotificationEntity(
    @PrimaryKey val notificationKey: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val bodyPreview: String,
    val hasReplyAction: Boolean = false,
    val isSensitiveHidden: Boolean = false,
    val isRead: Boolean = false,
    val isDismissed: Boolean = false,
    val postedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "activity_logs")
data class ActivityLogEntity(
    @PrimaryKey val logId: String,
    val category: String, // PAIRING, TRANSFER, CLIPBOARD, HANDOFF, SCREEN, SECURITY, AI
    val deviceId: String? = null,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey val ruleId: String,
    val title: String,
    val triggerType: String, // DEVICE_CONNECTED, FILE_RECEIVED, WIFI_HOME, DEVICE_DISCONNECTED, WORKSPACE_ACTIVE
    val actionType: String, // OFFER_CLIPBOARD, NOTIFY_DEVICES, PREPARE_PAIRING, SHOW_CONTROLS
    val enabled: Boolean = true,
    val lastTriggeredAt: Long? = null
)
