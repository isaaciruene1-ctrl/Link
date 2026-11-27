package com.example.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AssistantActionType
import com.example.ai.AssistantStructuredReply
import com.example.ai.EcosystemContextSnapshot
import com.example.connectivity.ConnectedPeerState
import com.example.connectivity.DiscoveredPeer
import com.example.connectivity.PairingState
import com.example.connectivity.RelayChannelUiState
import com.example.connectivity.WifiP2pGroupSnapshot
import com.example.connectivity.WifiP2pPeerInfo
import com.example.continuity.NowPlayingSnapshot
import com.example.core.LocalTelemetry
import com.example.core.PermissionHelper
import com.example.data.cloud.CloudHandoffDoc
import com.example.data.cloud.SharedWorkspaceDoc
import com.example.data.cloud.UserDeviceDoc
import com.example.data.local.ActivityLogEntity
import com.example.data.local.AutomationRuleEntity
import com.example.data.local.ClipboardEntryEntity
import com.example.data.local.LinkPreferences
import com.example.data.local.SyncedNotificationEntity
import com.example.data.local.ThemeMode
import com.example.data.local.TransferRecordEntity
import com.example.data.local.TrustedDeviceEntity
import com.example.di.LinkAppContainer
import com.example.input.LinkInputAccessibilityService
import com.example.input.MultiControlUiState
import com.example.screens.ScreenSessionUiState
import com.example.screens.StreamCodecMode
import com.example.screens.StreamQualityPreset
import com.example.security.QrPairingPayload
import com.example.transfer.ActiveTransferProgress
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface CloudUiState<out T> {
    data object Loading : CloudUiState<Nothing>
    data class Success<T>(val data: T) : CloudUiState<T>
    data class Error(val message: String) : CloudUiState<Nothing>
}

sealed interface AssistantUiState {
    data object Idle : AssistantUiState
    data object Loading : AssistantUiState
    data class Success(val reply: AssistantStructuredReply, val executed: Boolean = false) : AssistantUiState
    data class Error(val message: String) : AssistantUiState
}

class LinkViewModel(
    private val container: LinkAppContainer,
    private val currentUserId: String
) : ViewModel() {

    val localIdentity = container.identityManager.getOrCreateIdentity()

    val preferences: StateFlow<LinkPreferences> = container.preferencesRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), LinkPreferences())

    val telemetry: StateFlow<LocalTelemetry> = container.telemetryProvider.observeTelemetry()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), container.telemetryProvider.currentSnapshot())

    val connectedPeers: StateFlow<List<ConnectedPeerState>> = container.connectivityEngine.connectedPeers
    val pairingState: StateFlow<PairingState> = container.connectivityEngine.pairingState
    val connectivityDiagnostics: StateFlow<String> = container.connectivityEngine.diagnostics
    val localPort: StateFlow<Int> = container.connectivityEngine.localPort

    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = combine(
        container.nsdEngine.discoveredPeers,
        container.bleEngine.blePeers
    ) { nsd, ble ->
        (nsd + ble).distinctBy { it.deviceId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val nsdStatus: StateFlow<String> = container.nsdEngine.statusText
    val bleStatus: StateFlow<String> = container.bleEngine.bleStatus

    val trustedDevices: StateFlow<List<TrustedDeviceEntity>> = container.database.trustedDeviceDao()
        .observeTrustedDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val transferHistory: StateFlow<List<TransferRecordEntity>> = container.fileTransferEngine.transferHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activeTransfers: StateFlow<Map<String, ActiveTransferProgress>> = container.fileTransferEngine.activeProgress

    val clipboardEntries: StateFlow<List<ClipboardEntryEntity>> = container.clipboardEngine.recentEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val clipboardStatus: StateFlow<String> = container.clipboardEngine.statusMessage

    val syncedNotifications: StateFlow<List<SyncedNotificationEntity>> = container.handoffEngine.notifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val localHandoffBanners = container.handoffEngine.incomingHandoffs

    val activityLogs: StateFlow<List<ActivityLogEntity>> = container.database.activityLogDao()
        .observeRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val automationRules: StateFlow<List<AutomationRuleEntity>> = container.database.automationRuleDao()
        .observeRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val screenState: StateFlow<ScreenSessionUiState> = container.screenMirrorEngine.state
    val remoteFeedback: StateFlow<String?> = container.mediaAndRemoteEngine.lastRemoteFeedback
    val remoteNowPlaying: StateFlow<NowPlayingSnapshot?> = container.mediaAndRemoteEngine.remoteNowPlaying

    val wifiP2pPeers: StateFlow<List<WifiP2pPeerInfo>> = container.wifiP2pEngine.peers
    val wifiP2pGroupState: StateFlow<WifiP2pGroupSnapshot> = container.wifiP2pEngine.groupState
    val relayState: StateFlow<RelayChannelUiState> = container.remoteRelayEngine.state
    val multiControlState: StateFlow<MultiControlUiState> = container.inputControlEngine.state
    val accessibilityInputConnected: StateFlow<Boolean> = LinkInputAccessibilityService.serviceConnected

    val cloudDevices: StateFlow<CloudUiState<List<UserDeviceDoc>>> =
        container.cloudRepository.observeDevices(currentUserId)
            .map<List<UserDeviceDoc>, CloudUiState<List<UserDeviceDoc>>> { CloudUiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Cloud devices error", root)
                emit(CloudUiState.Error(root.message ?: "Failed to sync cloud devices"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val cloudWorkspaces: StateFlow<CloudUiState<List<SharedWorkspaceDoc>>> =
        container.cloudRepository.observeWorkspaces(currentUserId)
            .map<List<SharedWorkspaceDoc>, CloudUiState<List<SharedWorkspaceDoc>>> { CloudUiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Cloud workspaces error", root)
                emit(CloudUiState.Error(root.message ?: "Failed to sync workspaces"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    val cloudHandoffs: StateFlow<CloudUiState<List<CloudHandoffDoc>>> =
        container.cloudRepository.observeHandoffs(currentUserId)
            .map<List<CloudHandoffDoc>, CloudUiState<List<CloudHandoffDoc>>> { CloudUiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Cloud handoffs error", root)
                emit(CloudUiState.Error(root.message ?: "Failed to sync cloud handoffs"))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), CloudUiState.Loading)

    private val _assistantState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val assistantState: StateFlow<AssistantUiState> = _assistantState.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    init {
        container.connectivityEngine.startTransportAndDiscovery()
        container.remoteRelayEngine.startObservingSignals(currentUserId)
        publishCurrentDeviceToCloud()
    }

    fun publishCurrentDeviceToCloud() {
        viewModelScope.launch {
            val prefs = preferences.value
            if (!prefs.cloudWorkspaceSyncOptIn) return@launch
            val snap = telemetry.value
            container.cloudRepository.upsertDevicePresence(
                deviceId = localIdentity.deviceId,
                deviceName = prefs.deviceDisplayName,
                manufacturer = snap.manufacturer,
                model = snap.model,
                deviceType = snap.formFactor.name,
                groupName = "My Devices",
                publicKeyFingerprint = localIdentity.fingerprintShort,
                batteryLevel = snap.batteryPercent,
                charging = snap.isCharging,
                networkType = snap.networkType.name,
                online = true,
                trusted = true
            )
        }
    }

    fun scanNearbyDevices() {
        container.connectivityEngine.triggerManualScan()
    }

    fun pairWithDiscoveredPeer(peer: DiscoveredPeer) {
        container.connectivityEngine.pairWithDiscoveredPeer(peer)
    }

    fun pairWithQrString(raw: String) {
        val decoded = QrPairingPayload.decode(raw)
        if (decoded == null) {
            _bannerMessage.value = "Invalid LINK QR pairing payload."
            return
        }
        container.connectivityEngine.pairWithQrPayload(decoded)
    }

    fun buildQrPairingPayload(): QrPairingPayload? =
        container.connectivityEngine.buildLocalQrPayload(preferences.value.deviceDisplayName)

    fun confirmPairing(sessionTempId: String, accepted: Boolean) {
        container.connectivityEngine.confirmPendingPairing(sessionTempId, accepted)
    }

    fun resetPairingState() {
        container.connectivityEngine.resetPairingState()
    }

    fun updateDeviceGroupAndPermissions(device: TrustedDeviceEntity) {
        viewModelScope.launch {
            container.database.trustedDeviceDao().upsert(device)
            if (preferences.value.cloudWorkspaceSyncOptIn) {
                val primaryGroup = device.groupsCsv.split(",").firstOrNull()?.trim().orEmpty().ifBlank { "My Devices" }
                container.cloudRepository.upsertDevicePresence(
                    deviceId = device.deviceId,
                    deviceName = device.displayName,
                    manufacturer = device.manufacturer,
                    model = device.model,
                    deviceType = device.deviceType,
                    groupName = primaryGroup,
                    publicKeyFingerprint = device.fingerprintShort,
                    batteryLevel = -1,
                    charging = false,
                    networkType = "WIFI",
                    online = connectedPeers.value.any { it.deviceId == device.deviceId },
                    trusted = device.isTrusted
                )
            }
        }
    }

    fun revokeAndRemoveDevice(deviceId: String) {
        viewModelScope.launch {
            container.connectivityEngine.revokeAndForgetDevice(deviceId)
            container.cloudRepository.removeDevice(deviceId)
            _bannerMessage.value = "Device trust revoked and removed."
        }
    }

    fun disconnectAllDevices() {
        viewModelScope.launch {
            container.connectivityEngine.disconnectAllDevices()
            _bannerMessage.value = "All active device sessions closed."
        }
    }

    fun sendFiles(uris: List<Uri>, targetDeviceIds: List<String>) {
        if (uris.isEmpty() || targetDeviceIds.isEmpty()) {
            _bannerMessage.value = "Select at least one file and one connected device."
            return
        }
        val names = connectedPeers.value.associate { it.deviceId to it.displayName }
        container.fileTransferEngine.sendFiles(uris, targetDeviceIds, names)
    }

    fun pauseTransfer(transferId: String) = container.fileTransferEngine.pauseTransfer(transferId)
    fun cancelTransfer(transferId: String) = container.fileTransferEngine.cancelTransfer(transferId)
    fun retryTransfer(record: TransferRecordEntity) = container.fileTransferEngine.retryTransfer(record)
    fun clearTransferHistory() = container.fileTransferEngine.clearHistory()

    fun sendClipboardToDevices(targetIds: List<String>, manualText: String? = null) {
        container.clipboardEngine.sendCurrentClipboardToDevices(targetIds, manualText)
    }

    fun copyToLocalClipboard(text: String) {
        container.clipboardEngine.copyToLocalClipboard(text)
    }

    fun clearClipboardHistory() {
        container.clipboardEngine.clearHistory()
    }

    fun sendHandoff(targetDeviceId: String?, actionType: String, title: String, payload: String) {
        container.handoffEngine.sendHandoff(targetDeviceId, actionType, title, payload)
    }

    fun continueHandoff(actionType: String, payload: String, cloudId: String? = null, localBannerId: String? = null) {
        val opened = container.handoffEngine.executeHandoffPayload(actionType, payload)
        if (!opened) {
            _bannerMessage.value = "Could not open handoff target on this device."
        }
        localBannerId?.let { container.handoffEngine.dismissLocalBanner(it) }
        if (cloudId != null) {
            viewModelScope.launch { container.cloudRepository.markHandoffConsumed(cloudId) }
        }
    }

    fun dismissHandoff(cloudId: String? = null, localBannerId: String? = null) {
        localBannerId?.let { container.handoffEngine.dismissLocalBanner(it) }
        if (cloudId != null) {
            viewModelScope.launch { container.cloudRepository.markHandoffConsumed(cloudId) }
        }
    }

    fun markNotificationRead(key: String) = container.handoffEngine.markNotificationRead(key)
    fun markAllNotificationsRead() = container.handoffEngine.markAllNotificationsRead()
    fun dismissNotification(item: SyncedNotificationEntity) = container.handoffEngine.dismissNotification(item)

    fun createWorkspace(title: String, notes: String, pinnedLink: String?, tasks: List<String>) {
        viewModelScope.launch {
            val res = container.cloudRepository.createWorkspace(
                title = title,
                notes = notes,
                pinnedLink = pinnedLink,
                tasks = tasks,
                activeDeviceName = preferences.value.deviceDisplayName
            )
            _bannerMessage.value = if (res.isSuccess) "Workspace '$title' synced" else res.exceptionOrNull()?.localizedMessage
        }
    }

    fun deleteWorkspace(workspaceId: String) {
        viewModelScope.launch {
            container.cloudRepository.deleteWorkspace(workspaceId)
        }
    }

    fun setScreenQuality(preset: StreamQualityPreset) = container.screenMirrorEngine.setQuality(preset)
    fun setScreenCodecMode(mode: StreamCodecMode) = container.screenMirrorEngine.setCodecMode(mode)
    fun attachScreenDecoderSurface(surface: Surface?) = container.screenMirrorEngine.attachDecoderSurface(surface)
    fun createScreenConsentIntent(): Intent = container.screenMirrorEngine.createCaptureConsentIntent()
    fun startScreenProjection(resultCode: Int, data: Intent, targetId: String, targetName: String) {
        container.screenMirrorEngine.beginProjectionCapture(resultCode, data, targetId, targetName)
    }
    fun stopScreenProjection() = container.screenMirrorEngine.stopProjection()

    fun sendMediaCommand(deviceId: String, command: String) = container.mediaAndRemoteEngine.sendMediaCommand(deviceId, command)
    fun requestRemoteMediaState(deviceId: String) = container.mediaAndRemoteEngine.requestRemoteMediaState(deviceId)
    fun locateDevice(deviceId: String) = container.mediaAndRemoteEngine.triggerRemoteLocateChime(deviceId)

    fun discoverWifiDirectPeers() = container.wifiP2pEngine.discoverPeers()
    fun createWifiDirectGroup() = container.wifiP2pEngine.createDirectGroup()
    fun connectWifiDirectPeer(address: String) = container.wifiP2pEngine.connectToPeer(address)
    fun removeWifiDirectGroup() = container.wifiP2pEngine.removeGroup()

    fun initiateRemoteSignaling(deviceId: String) {
        container.remoteRelayEngine.initiateWebRtcSignalingOffer(
            targetDeviceId = deviceId,
            localHost = container.connectivityEngine.getLocalIpv4Address(),
            localPort = container.connectivityEngine.localPort.value
        )
    }

    fun sendRemoteWakePing(deviceId: String) = container.remoteRelayEngine.sendWakePing(deviceId)

    fun setInputReceiverArmed(armed: Boolean) = container.inputControlEngine.setReceiverArmed(armed)
    fun sendRemoteTap(deviceId: String, nx: Float, ny: Float) = container.inputControlEngine.sendTap(deviceId, nx, ny)
    fun sendRemoteSwipe(deviceId: String, sx: Float, sy: Float, ex: Float, ey: Float) =
        container.inputControlEngine.sendSwipe(deviceId, sx, sy, ex, ey)
    fun sendRemoteNav(deviceId: String, action: String) = container.inputControlEngine.sendNavigationAction(deviceId, action)
    fun sendRemoteText(deviceId: String, text: String) = container.inputControlEngine.sendVirtualText(deviceId, text)

    fun setDeviceDisplayName(name: String) {
        viewModelScope.launch {
            container.preferencesRepository.setDeviceDisplayName(name)
            publishCurrentDeviceToCloud()
        }
    }

    fun setAutoDiscovery(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setAutoDiscovery(enabled) }
    }

    fun setClipboardSyncOptIn(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setClipboardSyncOptIn(enabled) }
    }

    fun setClipboardSensitiveFilter(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setClipboardSensitiveFilter(enabled) }
    }

    fun setNotificationSyncOptIn(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setNotificationSyncOptIn(enabled) }
    }

    fun setHideSensitiveNotifications(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setHideSensitiveNotifications(enabled) }
    }

    fun setCloudWorkspaceSyncOptIn(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setCloudWorkspaceSyncOptIn(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.preferencesRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setDynamicColor(enabled) }
    }

    fun setOnboardingCompleted() {
        viewModelScope.launch { container.preferencesRepository.setOnboardingCompleted(true) }
    }

    fun toggleAutomationRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch { container.database.automationRuleDao().setEnabled(ruleId, enabled) }
    }

    fun askGeminiAssistant(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _assistantState.value = AssistantUiState.Loading
            val connected = connectedPeers.value
            val paired = trustedDevices.value
            val transfers = transferHistory.value.take(5)
            val notifs = syncedNotifications.value.take(5)
            val workspaces = (cloudWorkspaces.value as? CloudUiState.Success)?.data.orEmpty()
            val snap = telemetry.value

            val ctx = EcosystemContextSnapshot(
                localDeviceName = preferences.value.deviceDisplayName,
                localBatteryPercent = snap.batteryPercent,
                localNetworkType = snap.networkType.name,
                connectedDevicesSummary = if (connected.isEmpty()) "None" else connected.joinToString {
                    "${it.displayName} (Battery=${it.batteryPercent ?: "unknown"}%, RTT=${it.rttMs ?: "?"}ms)"
                },
                pairedDevicesSummary = if (paired.isEmpty()) "None" else paired.joinToString { "${it.displayName} [${it.groupsCsv}]" },
                recentTransfersSummary = if (transfers.isEmpty()) "None" else transfers.joinToString { "${it.fileName} (${it.status})" },
                notificationCount = syncedNotifications.value.size,
                recentNotificationTitles = if (notifs.isEmpty()) "None" else notifs.joinToString { "${it.appName}: ${it.title}" },
                workspaceCount = workspaces.size
            )

            val result = container.assistantRepository.askAssistant(prompt, ctx)
            _assistantState.value = result.fold(
                onSuccess = { reply -> AssistantUiState.Success(reply, executed = !reply.requiresConfirmation) },
                onFailure = { err -> AssistantUiState.Error(err.localizedMessage ?: "Assistant request failed.") }
            )
        }
    }

    fun confirmAndExecuteAssistantAction(reply: AssistantStructuredReply) {
        val targetDevice = connectedPeers.value.firstOrNull {
            reply.targetDeviceName != null && it.displayName.contains(reply.targetDeviceName, ignoreCase = true)
        } ?: connectedPeers.value.firstOrNull()

        when (reply.actionType) {
            AssistantActionType.START_DISCOVERY -> scanNearbyDevices()
            AssistantActionType.SEND_CLIPBOARD -> {
                val targets = targetDevice?.let { listOf(it.deviceId) } ?: connectedPeers.value.map { it.deviceId }
                sendClipboardToDevices(targets, reply.payloadBody)
            }
            AssistantActionType.CREATE_HANDOFF -> {
                val payload = reply.payloadBody ?: return
                sendHandoff(targetDevice?.deviceId, "OPEN_URL", reply.payloadTitle ?: "AI Handoff", payload)
            }
            AssistantActionType.CREATE_WORKSPACE -> {
                createWorkspace(
                    title = reply.payloadTitle ?: "New LINK Workspace",
                    notes = reply.payloadBody ?: "",
                    pinnedLink = null,
                    tasks = emptyList()
                )
            }
            AssistantActionType.LOCATE_DEVICE -> {
                targetDevice?.let { locateDevice(it.deviceId) }
            }
            AssistantActionType.ANSWER_INFO, AssistantActionType.SUMMARIZE_NOTIFICATIONS -> Unit
        }
        _assistantState.value = AssistantUiState.Success(reply, executed = true)
    }

    fun dismissBanner() {
        _bannerMessage.value = null
        container.mediaAndRemoteEngine.clearFeedback()
    }

    suspend fun cleanupBeforeSignOut() {
        container.remoteRelayEngine.stopObserving()
        container.wifiP2pEngine.stop()
        container.connectivityEngine.stopAll()
    }

    companion object {
        private const val TAG = "LinkViewModel"
    }
}
