package com.example.di

import android.content.Context
import com.example.R
import com.example.ai.LinkAssistantRepository
import com.example.connectivity.BleDiscoveryEngine
import com.example.connectivity.ConnectivityManagerEngine
import com.example.connectivity.NsdDiscoveryEngine
import com.example.connectivity.RemoteRelayEngine
import com.example.connectivity.WifiP2pEngine
import com.example.continuity.MediaAndRemoteEngine
import com.example.continuity.SmartHandoffEngine
import com.example.continuity.UniversalClipboardEngine
import com.example.core.SystemTelemetryProvider
import com.example.data.cloud.CloudEcosystemRepository
import com.example.data.local.AutomationRuleEntity
import com.example.data.local.LinkDatabase
import com.example.data.local.LinkPreferencesRepository
import com.example.input.InputControlEngine
import com.example.screens.ScreenMirrorEngine
import com.example.security.DeviceIdentityManager
import com.example.transfer.FileTransferEngine
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LinkAppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: LinkDatabase = LinkDatabase.build(appContext)
    val preferencesRepository: LinkPreferencesRepository = LinkPreferencesRepository(appContext)
    val identityManager: DeviceIdentityManager = DeviceIdentityManager()
    val telemetryProvider: SystemTelemetryProvider = SystemTelemetryProvider(appContext)

    val firestoreDb: FirebaseFirestore = FirebaseFirestore.getInstance(
        appContext.getString(R.string.firestore_database_id)
    )
    val cloudRepository: CloudEcosystemRepository = CloudEcosystemRepository(firestoreDb)

    val nsdEngine: NsdDiscoveryEngine = NsdDiscoveryEngine(appContext)
    val bleEngine: BleDiscoveryEngine = BleDiscoveryEngine(appContext)
    val wifiP2pEngine: WifiP2pEngine = WifiP2pEngine(appContext)

    val connectivityEngine: ConnectivityManagerEngine = ConnectivityManagerEngine(
        context = appContext,
        identityManager = identityManager,
        telemetryProvider = telemetryProvider,
        nsdEngine = nsdEngine,
        bleEngine = bleEngine,
        trustedDeviceDao = database.trustedDeviceDao(),
        activityLogDao = database.activityLogDao(),
        preferencesRepository = preferencesRepository
    )

    val remoteRelayEngine: RemoteRelayEngine = RemoteRelayEngine(
        connectivityEngine = connectivityEngine,
        identityManager = identityManager,
        trustedDeviceDao = database.trustedDeviceDao(),
        activityLogDao = database.activityLogDao(),
        preferencesRepository = preferencesRepository,
        cloudRepository = cloudRepository
    )

    val fileTransferEngine: FileTransferEngine = FileTransferEngine(
        context = appContext,
        connectivityEngine = connectivityEngine,
        transferRecordDao = database.transferRecordDao(),
        activityLogDao = database.activityLogDao()
    )

    val clipboardEngine: UniversalClipboardEngine = UniversalClipboardEngine(
        context = appContext,
        connectivityEngine = connectivityEngine,
        identityManager = identityManager,
        clipboardDao = database.clipboardEntryDao(),
        trustedDeviceDao = database.trustedDeviceDao(),
        activityLogDao = database.activityLogDao(),
        preferencesRepository = preferencesRepository,
        cloudRepository = cloudRepository
    )

    val handoffEngine: SmartHandoffEngine = SmartHandoffEngine(
        context = appContext,
        connectivityEngine = connectivityEngine,
        identityManager = identityManager,
        notificationDao = database.syncedNotificationDao(),
        activityLogDao = database.activityLogDao(),
        preferencesRepository = preferencesRepository,
        cloudRepository = cloudRepository
    )

    val screenMirrorEngine: ScreenMirrorEngine = ScreenMirrorEngine(
        context = appContext,
        connectivityEngine = connectivityEngine,
        activityLogDao = database.activityLogDao()
    )

    val mediaAndRemoteEngine: MediaAndRemoteEngine = MediaAndRemoteEngine(
        context = appContext,
        connectivityEngine = connectivityEngine,
        remoteRelayEngine = remoteRelayEngine,
        trustedDeviceDao = database.trustedDeviceDao(),
        activityLogDao = database.activityLogDao()
    )

    val inputControlEngine: InputControlEngine = InputControlEngine(
        context = appContext,
        connectivityEngine = connectivityEngine,
        remoteRelayEngine = remoteRelayEngine,
        trustedDeviceDao = database.trustedDeviceDao(),
        activityLogDao = database.activityLogDao()
    )

    val assistantRepository: LinkAssistantRepository = LinkAssistantRepository()

    init {
        wifiP2pEngine.initialize { groupOwnerHost, isGroupOwner ->
            if (!isGroupOwner) {
                val port = connectivityEngine.localPort.value.takeIf { it > 0 } ?: 51721
                connectivityEngine.connectToDirectHost(groupOwnerHost, port)
            }
        }
        initScope.launch {
            seedDefaultAutomationRulesIfEmpty()
        }
    }

    private suspend fun seedDefaultAutomationRulesIfEmpty() {
        val existing = database.automationRuleDao().observeRules().first()
        if (existing.isEmpty()) {
            val defaults = listOf(
                AutomationRuleEntity(
                    ruleId = "rule_clipboard_connect",
                    title = "Offer clipboard sync when a trusted tablet connects",
                    triggerType = "DEVICE_CONNECTED",
                    actionType = "OFFER_CLIPBOARD",
                    enabled = true
                ),
                AutomationRuleEntity(
                    ruleId = "rule_file_notify",
                    title = "Log and alert when an encrypted file transfer completes",
                    triggerType = "FILE_RECEIVED",
                    actionType = "NOTIFY_DEVICES",
                    enabled = true
                ),
                AutomationRuleEntity(
                    ruleId = "rule_disconnect_alert",
                    title = "Record diagnostic event when a paired device disconnects",
                    triggerType = "DEVICE_DISCONNECTED",
                    actionType = "NOTIFY_DEVICES",
                    enabled = true
                ),
                AutomationRuleEntity(
                    ruleId = "rule_workspace_controls",
                    title = "Surface pinned workspace links when opening Continuity",
                    triggerType = "WORKSPACE_ACTIVE",
                    actionType = "SHOW_CONTROLS",
                    enabled = false
                )
            )
            defaults.forEach { database.automationRuleDao().upsert(it) }
        }
    }
}
