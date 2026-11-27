package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.connectivity.ConnectedPeerState
import com.example.connectivity.RelayChannelUiState
import com.example.core.PermissionHelper
import com.example.data.local.AutomationRuleEntity
import com.example.data.local.LinkPreferences
import com.example.data.local.ThemeMode
import com.example.data.local.TrustedDeviceEntity
import com.example.ui.FeatureStatus
import com.example.ui.FeatureStatusCatalog
import com.example.ui.components.LinkSectionHeader
import com.example.ui.components.LinkStatusPill

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    userEmail: String?,
    preferences: LinkPreferences,
    connectedPeers: List<ConnectedPeerState>,
    trustedDevices: List<TrustedDeviceEntity>,
    automationRules: List<AutomationRuleEntity>,
    relayState: RelayChannelUiState,
    accessibilityConnected: Boolean,
    onUpdateDeviceName: (String) -> Unit,
    onToggleAutoDiscovery: (Boolean) -> Unit,
    onToggleClipboardSync: (Boolean) -> Unit,
    onToggleClipboardFilter: (Boolean) -> Unit,
    onToggleNotificationSync: (Boolean) -> Unit,
    onToggleHideSensitiveNotifs: (Boolean) -> Unit,
    onToggleCloudSync: (Boolean) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDisconnectAll: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var permissionSnapshot by remember { mutableStateOf(PermissionHelper.snapshot(context)) }
    var deviceNameDraft by remember(preferences.deviceDisplayName) { mutableStateOf(preferences.deviceDisplayName) }

    val multiPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionSnapshot = PermissionHelper.snapshot(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Privacy Dashboard
        item {
            LinkSectionHeader(
                title = "Privacy Dashboard",
                subtitle = "Active sessions, device trust, and zero-bypass permission status"
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(userEmail ?: "Signed-in Google Account", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${connectedPeers.size} active sessions · ${trustedDevices.size} trusted devices · Route: ${relayState.lastRouteUsed.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }

                    Button(
                        onClick = onDisconnectAll,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("disconnect_all_button")
                    ) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Disconnect All Devices")
                    }

                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign Out")
                    }
                }
            }
        }

        // Permissions Matrix (On-Demand)
        item {
            LinkSectionHeader(
                title = "Android Permissions",
                subtitle = "Requested only when you activate the corresponding feature"
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PermissionRow(
                        title = "Nearby Wi-Fi Devices",
                        granted = permissionSnapshot.nearbyWifiGranted,
                        onGrant = {
                            val perms = PermissionHelper.requiredNearbyWifiPermissions()
                            if (perms.isNotEmpty()) multiPermLauncher.launch(perms)
                        }
                    )
                    HorizontalDivider()
                    PermissionRow(
                        title = "Bluetooth LE Discovery",
                        granted = permissionSnapshot.bluetoothGranted,
                        onGrant = {
                            val perms = PermissionHelper.requiredBluetoothPermissions()
                            if (perms.isNotEmpty()) multiPermLauncher.launch(perms)
                        }
                    )
                    HorizontalDivider()
                    PermissionRow(
                        title = "Post Notifications",
                        granted = permissionSnapshot.notificationsGranted,
                        onGrant = {
                            val perms = PermissionHelper.requiredNotificationPermission()
                            if (perms.isNotEmpty()) multiPermLauncher.launch(perms)
                        }
                    )
                    HorizontalDivider()
                    PermissionRow(
                        title = "Notification Listener Access",
                        granted = permissionSnapshot.notificationListenerGranted,
                        onGrant = { PermissionHelper.openNotificationListenerSettings(context) }
                    )
                    HorizontalDivider()
                    PermissionRow(
                        title = "Multi-Control Accessibility Input",
                        granted = permissionSnapshot.accessibilityInputGranted || accessibilityConnected,
                        onGrant = { PermissionHelper.openAccessibilitySettings(context) }
                    )
                }
            }
        }

        // Identity, Theme & Sync Toggles
        item {
            LinkSectionHeader(title = "Device & Appearance")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = deviceNameDraft,
                            onValueChange = { deviceNameDraft = it },
                            label = { Text("Device Display Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { onUpdateDeviceName(deviceNameDraft) },
                            enabled = deviceNameDraft.isNotBlank() && deviceNameDraft != preferences.deviceDisplayName,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Save")
                        }
                    }

                    Text("Theme Mode", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.themeMode == mode,
                                onClick = { onSelectThemeMode(mode) },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }

                    SettingsSwitchRow("Material You Dynamic Color", "Use Android 12+ wallpaper palette", preferences.dynamicColorEnabled, onToggleDynamicColor)
                    SettingsSwitchRow("Automatic LAN Discovery", "Advertise & scan NSD when LINK is active", preferences.autoDiscoveryEnabled, onToggleAutoDiscovery)
                    SettingsSwitchRow("Universal Clipboard Opt-In", "Accept incoming foreground clipboard pushes", preferences.clipboardSyncOptIn, onToggleClipboardSync)
                    SettingsSwitchRow("Sensitive Clipboard Filter", "Block passwords, API keys, and OTPs", preferences.clipboardSensitiveFilter, onToggleClipboardFilter)
                    SettingsSwitchRow("Notification Mirroring", "Broadcast non-sensitive notifications to paired devices", preferences.notificationSyncOptIn, onToggleNotificationSync)
                    SettingsSwitchRow("Hide Sensitive Notifications", "Mask private/lockscreen content by default", preferences.hideSensitiveNotifications, onToggleHideSensitiveNotifs)
                    SettingsSwitchRow("Cloud Workspace & Handoff Sync", "Sync device presence and workspaces via Firestore", preferences.cloudWorkspaceSyncOptIn, onToggleCloudSync)
                }
            }
        }

        // Automation Rules
        item {
            LinkSectionHeader(
                title = "Automation Engine",
                subtitle = "Event-driven rules without permanent background polling"
            )
        }
        items(automationRules, key = { it.ruleId }) { rule ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(rule.title, style = MaterialTheme.typography.bodyMedium)
                        Text("${rule.triggerType} -> ${rule.actionType}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { onToggleRule(rule.ruleId, it) }
                    )
                }
            }
        }

        // Feature Status Ledger
        item {
            LinkSectionHeader(
                title = "Feature Status Ledger",
                subtitle = "Transparent engineering readiness & hardware testing status"
            )
        }
        items(FeatureStatusCatalog.items, key = { it.module }) { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.module, style = MaterialTheme.typography.titleSmall)
                        LinkStatusPill(
                            label = item.status.label,
                            active = item.status == FeatureStatus.FULLY_IMPLEMENTED_AND_TESTED ||
                                item.status == FeatureStatus.IMPLEMENTED_NOT_FULLY_TESTED
                        )
                    }
                    Text(item.capability, style = MaterialTheme.typography.bodySmall)
                    Text(item.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (granted) {
            LinkStatusPill(label = "Granted", active = true)
        } else {
            TextButton(onClick = onGrant) { Text("Enable") }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
