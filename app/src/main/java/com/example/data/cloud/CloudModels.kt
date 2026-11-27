package com.example.data.cloud

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserDeviceDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val deviceName: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val deviceType: String = "PHONE",
    val groupName: String = "My Devices",
    val publicKeyFingerprint: String = "",
    val batteryLevel: Long = -1L,
    val charging: Boolean = false,
    val networkType: String = "UNKNOWN",
    val online: Boolean = true,
    val trusted: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SharedWorkspaceDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val notes: String = "",
    val pinnedLink: String? = null,
    val tasks: List<String> = emptyList(),
    val activeDeviceName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudHandoffDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sourceDeviceId: String = "",
    val sourceDeviceName: String = "",
    val targetDeviceId: String = "ALL",
    val actionType: String = "OPEN_URL",
    val title: String = "",
    val payload: String = "",
    val consumed: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class RelaySignalDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val roomId: String = "",
    val senderDeviceId: String = "",
    val senderDeviceName: String = "",
    val targetDeviceId: String = "",
    val signalType: String = "ENCRYPTED_RELAY_PACKET",
    val payload: String = "",
    val consumed: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
