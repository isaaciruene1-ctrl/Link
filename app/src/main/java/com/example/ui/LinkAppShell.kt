package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.LinkApplication
import com.example.auth.GoogleAuthManager
import com.example.connectivity.PairingState
import com.example.data.local.ThemeMode
import com.example.data.local.TrustedDeviceEntity
import com.example.ui.components.AssistantBottomSheet
import com.example.ui.components.DeviceDetailSheet
import com.example.ui.components.LinkStatusPill
import com.example.ui.components.PairingSheetDialog
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ContinuityScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.OnboardingDialog
import com.example.ui.screens.ScreensScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TransferScreen
import com.example.ui.theme.LinkTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

enum class LinkTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("home", "Home", Icons.Filled.Devices, Icons.Outlined.Devices),
    CONTINUITY("continuity", "Continuity", Icons.Filled.Hub, Icons.Outlined.Hub),
    SCREENS("screens", "Screens", Icons.Filled.Cast, Icons.Outlined.Cast),
    TRANSFER("transfer", "Transfer", Icons.Filled.SwapHoriz, Icons.Outlined.SwapHoriz),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun LinkRootApp(sharedIncomingText: String?) {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        LinkTheme(darkTheme = true) {
            AuthScreen(
                onAuthSuccess = { currentUser = Firebase.auth.currentUser }
            )
        }
    } else {
        key(user.uid) {
            AuthenticatedLinkShell(
                currentUserId = user.uid,
                userEmail = user.email,
                sharedIncomingText = sharedIncomingText
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedLinkShell(
    currentUserId: String,
    userEmail: String?,
    sharedIncomingText: String?
) {
    val context = LocalContext.current
    val app = context.applicationContext as LinkApplication
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: LinkViewModel = viewModel(
        key = currentUserId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LinkViewModel(app.container, currentUserId) as T
            }
        }
    )

    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val trustedDevices by viewModel.trustedDevices.collectAsStateWithLifecycle()
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val localPort by viewModel.localPort.collectAsStateWithLifecycle()
    val diagnostics by viewModel.connectivityDiagnostics.collectAsStateWithLifecycle()
    val nsdStatus by viewModel.nsdStatus.collectAsStateWithLifecycle()
    val bleStatus by viewModel.bleStatus.collectAsStateWithLifecycle()
    val recentLogs by viewModel.activityLogs.collectAsStateWithLifecycle()
    val clipboardEntries by viewModel.clipboardEntries.collectAsStateWithLifecycle()
    val clipboardStatus by viewModel.clipboardStatus.collectAsStateWithLifecycle()
    val syncedNotifications by viewModel.syncedNotifications.collectAsStateWithLifecycle()
    val localHandoffs by viewModel.localHandoffBanners.collectAsStateWithLifecycle()
    val cloudHandoffs by viewModel.cloudHandoffs.collectAsStateWithLifecycle()
    val cloudWorkspaces by viewModel.cloudWorkspaces.collectAsStateWithLifecycle()
    val activeTransfers by viewModel.activeTransfers.collectAsStateWithLifecycle()
    val transferHistory by viewModel.transferHistory.collectAsStateWithLifecycle()
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val remoteNowPlaying by viewModel.remoteNowPlaying.collectAsStateWithLifecycle()
    val wifiP2pPeers by viewModel.wifiP2pPeers.collectAsStateWithLifecycle()
    val wifiP2pGroupState by viewModel.wifiP2pGroupState.collectAsStateWithLifecycle()
    val cloudDevices by viewModel.cloudDevices.collectAsStateWithLifecycle()
    val relayState by viewModel.relayState.collectAsStateWithLifecycle()
    val multiControlState by viewModel.multiControlState.collectAsStateWithLifecycle()
    val accessibilityInputConnected by viewModel.accessibilityInputConnected.collectAsStateWithLifecycle()
    val automationRules by viewModel.automationRules.collectAsStateWithLifecycle()
    val assistantState by viewModel.assistantState.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
    val remoteFeedback by viewModel.remoteFeedback.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(LinkTab.HOME) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showAssistantSheet by remember { mutableStateOf(false) }
    var selectedTrustedDevice by remember { mutableStateOf<TrustedDeviceEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(bannerMessage, remoteFeedback) {
        val msg = bannerMessage ?: remoteFeedback
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    LaunchedEffect(sharedIncomingText) {
        if (!sharedIncomingText.isNullOrBlank()) {
            val isUrl = sharedIncomingText.startsWith("http://") || sharedIncomingText.startsWith("https://")
            viewModel.sendHandoff(
                targetDeviceId = null,
                actionType = if (isUrl) "OPEN_URL" else "CLIPBOARD_NOTE",
                title = "Shared from Android",
                payload = sharedIncomingText
            )
            activeTab = LinkTab.CONTINUITY
        }
    }

    LaunchedEffect(pairingState) {
        if (pairingState is PairingState.AwaitingUserConfirmation || pairingState is PairingState.Failed) {
            showQrDialog = true
        }
    }

    BackHandler(enabled = activeTab != LinkTab.HOME) {
        activeTab = LinkTab.HOME
    }

    val isDark = when (preferences.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    LinkTheme(darkTheme = isDark, dynamicColor = preferences.dynamicColorEnabled) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 700.dp

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("LINK", style = MaterialTheme.typography.headlineSmall)
                                LinkStatusPill(
                                    label = if (connectedPeers.isEmpty()) "Ready" else "${connectedPeers.size} Connected",
                                    active = connectedPeers.isNotEmpty()
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { showAssistantSheet = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("open_ai_assistant_fab")
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Open LINK AI Assistant")
                    }
                },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(windowInsets = WindowInsets.navigationBars) {
                            LinkTab.entries.forEach { tab ->
                                val selected = activeTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { activeTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag("nav_${tab.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(modifier = Modifier.fillMaxHeight()) {
                            LinkTab.entries.forEach { tab ->
                                val selected = activeTab == tab
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { activeTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag("rail_${tab.route}")
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 840.dp)
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            when (activeTab) {
                                LinkTab.HOME -> HomeScreen(
                                    localIdentity = viewModel.localIdentity,
                                    preferences = preferences,
                                    telemetry = telemetry,
                                    localPort = localPort,
                                    diagnostics = diagnostics,
                                    nsdStatus = nsdStatus,
                                    bleStatus = bleStatus,
                                    connectedPeers = connectedPeers,
                                    discoveredPeers = discoveredPeers,
                                    trustedDevices = trustedDevices,
                                    wifiP2pPeers = wifiP2pPeers,
                                    wifiP2pGroupState = wifiP2pGroupState,
                                    cloudDevices = cloudDevices,
                                    relayState = relayState,
                                    recentLogs = recentLogs,
                                    onScanNearby = viewModel::scanNearbyDevices,
                                    onOpenQrPairing = {
                                        viewModel.resetPairingState()
                                        showQrDialog = true
                                    },
                                    onPairDiscoveredPeer = viewModel::pairWithDiscoveredPeer,
                                    onSelectTrustedDevice = { selectedTrustedDevice = it },
                                    onScanWifiDirect = viewModel::discoverWifiDirectPeers,
                                    onCreateWifiDirectGroup = viewModel::createWifiDirectGroup,
                                    onRemoveWifiDirectGroup = viewModel::removeWifiDirectGroup,
                                    onConnectWifiDirectPeer = viewModel::connectWifiDirectPeer,
                                    onInitiateRelaySignaling = viewModel::initiateRemoteSignaling,
                                    onSendWakePing = viewModel::sendRemoteWakePing,
                                    onQuickTransfer = { activeTab = LinkTab.TRANSFER },
                                    onQuickClipboard = { activeTab = LinkTab.CONTINUITY },
                                    onQuickScreens = { activeTab = LinkTab.SCREENS }
                                )

                                LinkTab.CONTINUITY -> ContinuityScreen(
                                    preferences = preferences,
                                    connectedPeers = connectedPeers,
                                    clipboardEntries = clipboardEntries,
                                    clipboardStatus = clipboardStatus,
                                    syncedNotifications = syncedNotifications,
                                    localHandoffs = localHandoffs,
                                    cloudHandoffs = cloudHandoffs,
                                    cloudWorkspaces = cloudWorkspaces,
                                    remoteNowPlaying = remoteNowPlaying,
                                    onToggleClipboardOptIn = viewModel::setClipboardSyncOptIn,
                                    onSendClipboard = viewModel::sendClipboardToDevices,
                                    onCopyClipboard = viewModel::copyToLocalClipboard,
                                    onClearClipboard = viewModel::clearClipboardHistory,
                                    onSendHandoff = viewModel::sendHandoff,
                                    onContinueHandoff = viewModel::continueHandoff,
                                    onDismissHandoff = viewModel::dismissHandoff,
                                    onMarkNotificationRead = viewModel::markNotificationRead,
                                    onMarkAllNotificationsRead = viewModel::markAllNotificationsRead,
                                    onDismissNotification = viewModel::dismissNotification,
                                    onSendMediaCommand = viewModel::sendMediaCommand,
                                    onRefreshMediaState = viewModel::requestRemoteMediaState,
                                    onLocateDevice = viewModel::locateDevice,
                                    onCreateWorkspace = viewModel::createWorkspace,
                                    onDeleteWorkspace = viewModel::deleteWorkspace
                                )

                                LinkTab.SCREENS -> ScreensScreen(
                                    screenState = screenState,
                                    connectedPeers = connectedPeers,
                                    multiControlState = multiControlState,
                                    accessibilityConnected = accessibilityInputConnected,
                                    onSelectQuality = viewModel::setScreenQuality,
                                    onSelectCodecMode = viewModel::setScreenCodecMode,
                                    onAttachDecoderSurface = viewModel::attachScreenDecoderSurface,
                                    createConsentIntent = viewModel::createScreenConsentIntent,
                                    onStartProjection = viewModel::startScreenProjection,
                                    onStopProjection = viewModel::stopScreenProjection,
                                    onSetInputReceiverArmed = viewModel::setInputReceiverArmed,
                                    onSendRemoteTap = viewModel::sendRemoteTap,
                                    onSendRemoteSwipe = viewModel::sendRemoteSwipe,
                                    onSendRemoteNav = viewModel::sendRemoteNav,
                                    onSendRemoteText = viewModel::sendRemoteText
                                )

                                LinkTab.TRANSFER -> TransferScreen(
                                    connectedPeers = connectedPeers,
                                    activeTransfers = activeTransfers,
                                    transferHistory = transferHistory,
                                    onSendFiles = viewModel::sendFiles,
                                    onPauseTransfer = viewModel::pauseTransfer,
                                    onCancelTransfer = viewModel::cancelTransfer,
                                    onRetryTransfer = viewModel::retryTransfer,
                                    onClearHistory = viewModel::clearTransferHistory
                                )

                                LinkTab.SETTINGS -> SettingsScreen(
                                    userEmail = userEmail,
                                    preferences = preferences,
                                    connectedPeers = connectedPeers,
                                    trustedDevices = trustedDevices,
                                    automationRules = automationRules,
                                    relayState = relayState,
                                    accessibilityConnected = accessibilityInputConnected,
                                    onUpdateDeviceName = viewModel::setDeviceDisplayName,
                                    onToggleAutoDiscovery = viewModel::setAutoDiscovery,
                                    onToggleClipboardSync = viewModel::setClipboardSyncOptIn,
                                    onToggleClipboardFilter = viewModel::setClipboardSensitiveFilter,
                                    onToggleNotificationSync = viewModel::setNotificationSyncOptIn,
                                    onToggleHideSensitiveNotifs = viewModel::setHideSensitiveNotifications,
                                    onToggleCloudSync = viewModel::setCloudWorkspaceSyncOptIn,
                                    onSelectThemeMode = viewModel::setThemeMode,
                                    onToggleDynamicColor = viewModel::setDynamicColor,
                                    onToggleRule = viewModel::toggleAutomationRule,
                                    onDisconnectAll = viewModel::disconnectAllDevices,
                                    onSignOut = {
                                        GoogleAuthManager.signOut(
                                            credentialManager = credentialManager,
                                            scope = scope,
                                            onPreSignOutCleanup = { viewModel.cleanupBeforeSignOut() }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (!preferences.onboardingCompleted) {
                OnboardingDialog(onComplete = viewModel::setOnboardingCompleted)
            }

            if (showQrDialog) {
                val qrPayload = remember(showQrDialog, localPort) {
                    viewModel.buildQrPairingPayload()?.encode()
                }
                val qrBitmap = remember(qrPayload) {
                    qrPayload?.let { app.container.identityManager.generateQrBitmap(it, 480) }
                }
                PairingSheetDialog(
                    pairingState = pairingState,
                    qrPayloadEncoded = qrPayload,
                    qrBitmap = qrBitmap,
                    onCopyPayload = { token ->
                        viewModel.copyToLocalClipboard(token)
                    },
                    onSubmitQrPayload = viewModel::pairWithQrString,
                    onConfirmSas = viewModel::confirmPairing,
                    onDismiss = {
                        viewModel.resetPairingState()
                        showQrDialog = false
                    }
                )
            }

            selectedTrustedDevice?.let { device ->
                val live = connectedPeers.firstOrNull { it.deviceId == device.deviceId }
                DeviceDetailSheet(
                    device = device,
                    isOnline = live != null,
                    rttMs = live?.rttMs,
                    batteryPercent = live?.batteryPercent,
                    onUpdateDevice = { updated ->
                        selectedTrustedDevice = updated
                        viewModel.updateDeviceGroupAndPermissions(updated)
                    },
                    onSendMediaCmd = { cmd -> viewModel.sendMediaCommand(device.deviceId, cmd) },
                    onLocateDevice = { viewModel.locateDevice(device.deviceId) },
                    onRevokeDevice = {
                        viewModel.revokeAndRemoveDevice(device.deviceId)
                        selectedTrustedDevice = null
                    },
                    onDismiss = { selectedTrustedDevice = null }
                )
            }

            if (showAssistantSheet) {
                AssistantBottomSheet(
                    state = assistantState,
                    onAsk = viewModel::askGeminiAssistant,
                    onConfirmAction = viewModel::confirmAndExecuteAssistantAction,
                    onDismiss = { showAssistantSheet = false }
                )
            }
        }
    }
}
