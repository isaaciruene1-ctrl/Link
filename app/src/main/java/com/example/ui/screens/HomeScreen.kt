package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.connectivity.ConnectedPeerState
import com.example.connectivity.DiscoveredPeer
import com.example.connectivity.DiscoveryTransport
import com.example.connectivity.RelayChannelUiState
import com.example.connectivity.WifiP2pGroupSnapshot
import com.example.connectivity.WifiP2pPeerInfo
import com.example.core.DeviceFormFactor
import com.example.core.LocalTelemetry
import com.example.data.cloud.UserDeviceDoc
import com.example.data.local.ActivityLogEntity
import com.example.data.local.LinkPreferences
import com.example.data.local.TrustedDeviceEntity
import com.example.security.LocalDeviceIdentity
import com.example.ui.CloudUiState
import com.example.ui.components.CloudRelayDevicesSection
import com.example.ui.components.LinkEmptyStateCard
import com.example.ui.components.LinkSectionHeader
import com.example.ui.components.LinkStatusPill
import com.example.ui.components.WifiDirectSectionCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    localIdentity: LocalDeviceIdentity,
    preferences: LinkPreferences,
    telemetry: LocalTelemetry,
    localPort: Int,
    diagnostics: String,
    nsdStatus: String,
    bleStatus: String,
    connectedPeers: List<ConnectedPeerState>,
    discoveredPeers: List<DiscoveredPeer>,
    trustedDevices: List<TrustedDeviceEntity>,
    wifiP2pPeers: List<WifiP2pPeerInfo>,
    wifiP2pGroupState: WifiP2pGroupSnapshot,
    cloudDevices: CloudUiState<List<UserDeviceDoc>>,
    relayState: RelayChannelUiState,
    recentLogs: List<ActivityLogEntity>,
    onScanNearby: () -> Unit,
    onOpenQrPairing: () -> Unit,
    onPairDiscoveredPeer: (DiscoveredPeer) -> Unit,
    onSelectTrustedDevice: (TrustedDeviceEntity) -> Unit,
    onScanWifiDirect: () -> Unit,
    onCreateWifiDirectGroup: () -> Unit,
    onRemoveWifiDirectGroup: () -> Unit,
    onConnectWifiDirectPeer: (String) -> Unit,
    onInitiateRelaySignaling: (String) -> Unit,
    onSendWakePing: (String) -> Unit,
    onQuickTransfer: () -> Unit,
    onQuickClipboard: () -> Unit,
    onQuickScreens: () -> Unit
) {
    var selectedGroup by remember { mutableStateOf("All") }
    val groups = listOf("All", "My Devices", "Work Devices", "Home Devices", "Temporary Devices")

    val filteredTrusted = remember(trustedDevices, selectedGroup) {
        if (selectedGroup == "All") trustedDevices
        else trustedDevices.filter { it.groupsCsv.contains(selectedGroup, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Local Device Hub Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (telemetry.formFactor == DeviceFormFactor.TABLET) {
                                        Icons.Filled.TabletAndroid
                                    } else {
                                        Icons.Filled.PhoneAndroid
                                    },
                                    contentDescription = "This Device",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column {
                                Text(
                                    text = preferences.deviceDisplayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${telemetry.manufacturer} ${telemetry.model} · ${telemetry.androidVersionLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        LinkStatusPill(
                            label = "${connectedPeers.size} Live",
                            active = connectedPeers.isNotEmpty()
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TelemetryBadge(
                            icon = if (telemetry.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                            text = if (telemetry.batteryPercent >= 0) "${telemetry.batteryPercent}%" else "Battery N/A"
                        )
                        TelemetryBadge(
                            icon = Icons.Filled.Wifi,
                            text = telemetry.networkType.name
                        )
                        TelemetryBadge(
                            icon = Icons.Filled.Radar,
                            text = if (localPort > 0) "Port $localPort" else "Binding..."
                        )
                    }

                    Text(
                        text = "Key ID ${localIdentity.fingerprintShort} · $diagnostics",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onScanNearby,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("scan_nearby_button")
                        ) {
                            Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan Nearby")
                        }
                        OutlinedButton(
                            onClick = onOpenQrPairing,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("open_qr_pair_button")
                        ) {
                            Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pair / QR")
                        }
                    }
                }
            }
        }

        // Quick Actions Strip
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionChip(
                    icon = Icons.Filled.FileUpload,
                    label = "Quick Transfer",
                    onClick = onQuickTransfer,
                    testTag = "quick_transfer_chip"
                )
                QuickActionChip(
                    icon = Icons.Filled.ContentPasteGo,
                    label = if (preferences.clipboardSyncOptIn) "Clipboard: On" else "Clipboard: Opt-In",
                    onClick = onQuickClipboard,
                    testTag = "quick_clipboard_chip"
                )
                QuickActionChip(
                    icon = Icons.AutoMirrored.Filled.ScreenShare,
                    label = "Screen Share",
                    onClick = onQuickScreens,
                    testTag = "quick_screens_chip"
                )
                QuickActionChip(
                    icon = Icons.Filled.Notifications,
                    label = if (preferences.notificationSyncOptIn) "Notifs: On" else "Notifs: Off",
                    onClick = onQuickClipboard,
                    testTag = "quick_notifs_chip"
                )
            }
        }

        // Nearby Discovered Devices
        item {
            LinkSectionHeader(
                title = "Nearby Discovery",
                subtitle = "$nsdStatus · $bleStatus"
            )
        }

        if (discoveredPeers.isEmpty()) {
            item {
                LinkEmptyStateCard(
                    icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                    title = "No Nearby LINK Devices Found",
                    description = "Keep LINK open on a second Android device on the same Wi-Fi network, or pair directly using QR code.",
                    actionLabel = "Scan Again",
                    onAction = onScanNearby,
                    testTag = "empty_scan_button"
                )
            }
        } else {
            items(discoveredPeers, key = { it.deviceId }) { peer ->
                val isAlreadyConnected = connectedPeers.any { it.deviceId == peer.deviceId }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPairDiscoveredPeer(peer) }
                        .testTag("discovered_peer_${peer.deviceId}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${peer.manufacturer} ${peer.model} · ${
                                    if (peer.transport == DiscoveryTransport.BLE) "BLE Beacon (${peer.signalRssi ?: 0} dBm)"
                                    else "${peer.host}:${peer.port}"
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { onPairDiscoveredPeer(peer) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text(if (isAlreadyConnected) "Connected" else "Pair")
                        }
                    }
                }
            }
        }

        // Paired & Trusted Devices with Group Filter
        item {
            LinkSectionHeader(
                title = "Paired Devices",
                subtitle = "Tap a trusted device for controls and per-device permissions"
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    FilterChip(
                        selected = selectedGroup == group,
                        onClick = { selectedGroup = group },
                        label = { Text(group) }
                    )
                }
            }
        }

        if (filteredTrusted.isEmpty()) {
            item {
                LinkEmptyStateCard(
                    icon = Icons.Filled.Devices,
                    title = "No Paired Devices Yet",
                    description = "Pair two Android devices via Wi-Fi discovery or QR code to unlock instant transfers and continuity.",
                    actionLabel = "Pair via QR",
                    onAction = onOpenQrPairing,
                    testTag = "empty_pair_qr_button"
                )
            }
        } else {
            items(filteredTrusted, key = { it.deviceId }) { device ->
                val live = connectedPeers.firstOrNull { it.deviceId == device.deviceId }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTrustedDevice(device) }
                        .testTag("trusted_device_${device.deviceId}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        1.dp,
                        if (live != null) MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${device.manufacturer} ${device.model} · ${device.groupsCsv}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Key ${device.fingerprintShort}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinkStatusPill(
                            label = if (live != null) "Online${live.rttMs?.let { " · ${it}ms" } ?: ""}" else "Offline",
                            active = live != null
                        )
                    }
                }
            }
        }

        // Wi-Fi Direct High-Speed P2P
        item {
            WifiDirectSectionCard(
                peers = wifiP2pPeers,
                groupState = wifiP2pGroupState,
                onScanP2p = onScanWifiDirect,
                onCreateGroup = onCreateWifiDirectGroup,
                onRemoveGroup = onRemoveWifiDirectGroup,
                onConnectPeer = onConnectWifiDirectPeer
            )
        }

        // Cloud Remote Relay & Presence
        item {
            CloudRelayDevicesSection(
                localDeviceId = localIdentity.deviceId,
                cloudDevices = cloudDevices,
                relayState = relayState,
                onInitiateSignaling = onInitiateRelaySignaling,
                onSendWakePing = onSendWakePing
            )
        }

        // Recent Activity Feed
        item {
            AnimatedVisibility(visible = recentLogs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkSectionHeader(title = "Recent Activity")
                    recentLogs.take(6).forEach { log ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(log.summary, style = MaterialTheme.typography.bodyMedium)
                                    Text(log.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun QuickActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.Medium)
    }
}
