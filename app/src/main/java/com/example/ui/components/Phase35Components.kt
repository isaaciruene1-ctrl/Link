package com.example.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RecentActors
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.connectivity.ConnectedPeerState
import com.example.connectivity.RelayChannelUiState
import com.example.connectivity.WifiP2pGroupSnapshot
import com.example.connectivity.WifiP2pPeerInfo
import com.example.continuity.NowPlayingSnapshot
import com.example.core.PermissionHelper
import com.example.data.cloud.UserDeviceDoc
import com.example.input.MultiControlUiState
import com.example.ui.CloudUiState

@Composable
fun WifiDirectSectionCard(
    peers: List<WifiP2pPeerInfo>,
    groupState: WifiP2pGroupSnapshot,
    onScanP2p: () -> Unit,
    onCreateGroup: () -> Unit,
    onRemoveGroup: () -> Unit,
    onConnectPeer: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text("Wi-Fi Direct (High-Speed P2P)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = groupState.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                LinkStatusPill(
                    label = when {
                        groupState.isGroupFormed -> if (groupState.isGroupOwner) "Owner" else "P2P Live"
                        groupState.p2pEnabled -> "Ready"
                        else -> "Standby"
                    },
                    active = groupState.isGroupFormed
                )
            }

            if (!groupState.networkName.isNullOrBlank() || !groupState.groupOwnerAddress.isNullOrBlank()) {
                Text(
                    text = listOfNotNull(
                        groupState.networkName?.let { "SSID: $it" },
                        groupState.groupOwnerAddress?.let { "Host: $it" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onScanP2p,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("p2p_scan_button")
                ) {
                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan P2P")
                }
                if (groupState.isGroupFormed) {
                    OutlinedButton(
                        onClick = onRemoveGroup,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("p2p_leave_group_button")
                    ) {
                        Text("Leave Group")
                    }
                } else {
                    OutlinedButton(
                        onClick = onCreateGroup,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("p2p_create_group_button")
                    ) {
                        Text("Create Group")
                    }
                }
            }

            if (peers.isNotEmpty()) {
                peers.forEach { peer ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.deviceName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "${peer.deviceAddress} · ${peer.statusLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { onConnectPeer(peer.deviceAddress) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CloudRelayDevicesSection(
    localDeviceId: String,
    cloudDevices: CloudUiState<List<UserDeviceDoc>>,
    relayState: RelayChannelUiState,
    onInitiateSignaling: (String) -> Unit,
    onSendWakePing: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text("Cloud Remote Relay & Presence", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${relayState.lastSignalingEvent} · Route: ${relayState.lastRouteUsed.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                LinkStatusPill(
                    label = if (relayState.activeSignalsCount > 0) "${relayState.activeSignalsCount} Signals" else "E2EE Ready",
                    active = relayState.activeSignalsCount > 0
                )
            }

            when (cloudDevices) {
                CloudUiState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading account devices...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is CloudUiState.Error -> {
                    Text(
                        text = cloudDevices.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is CloudUiState.Success -> {
                    val remoteDevices = cloudDevices.data.filter { it.id != localDeviceId }
                    if (remoteDevices.isEmpty()) {
                        Text(
                            text = "Sign into LINK on a second Android device with this Google account for off-LAN E2EE relay and Wake Ping.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        remoteDevices.forEach { dev ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(dev.deviceName.ifBlank { dev.model }, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "${dev.manufacturer} ${dev.model} · ${dev.networkType}" +
                                                    if (dev.batteryLevel >= 0) " · ${dev.batteryLevel}%" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        LinkStatusPill(
                                            label = if (dev.online) "Online" else "Remote",
                                            active = dev.online
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(
                                            onClick = { onInitiateSignaling(dev.id) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .minimumInteractiveComponentSize()
                                                .testTag("relay_signal_${dev.id}")
                                        ) {
                                            Text("Signal / Connect")
                                        }
                                        OutlinedButton(
                                            onClick = { onSendWakePing(dev.id) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .minimumInteractiveComponentSize()
                                                .testTag("relay_wake_${dev.id}")
                                        ) {
                                            Text("Wake Ping")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaRemoteCard(
    connectedPeers: List<ConnectedPeerState>,
    remoteNowPlaying: NowPlayingSnapshot?,
    onSendMediaCommand: (String, String) -> Unit,
    onRefreshMediaState: (String) -> Unit,
    onLocateDevice: (String) -> Unit
) {
    var selectedTargetId by remember(connectedPeers) {
        mutableStateOf(connectedPeers.firstOrNull()?.deviceId)
    }
    val selectedPeer = connectedPeers.firstOrNull { it.deviceId == selectedTargetId }
        ?: connectedPeers.firstOrNull()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Media Continuity & Remote", style = MaterialTheme.typography.titleMedium)
                selectedPeer?.let { peer ->
                    IconButton(
                        onClick = { onRefreshMediaState(peer.deviceId) },
                        modifier = Modifier.testTag("media_refresh_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh Remote Now Playing")
                    }
                }
            }

            if (connectedPeers.isEmpty()) {
                Text(
                    text = "Connect a paired device to control media playback, adjust volume, or ring its Locate chime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    connectedPeers.forEach { peer ->
                        FilterChip(
                            selected = selectedPeer?.deviceId == peer.deviceId,
                            onClick = { selectedTargetId = peer.deviceId },
                            label = { Text(peer.displayName) }
                        )
                    }
                }
            }

            val snapshot = remoteNowPlaying
            if (snapshot != null && (selectedPeer == null || snapshot.sourceDeviceId == selectedPeer.deviceId)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = snapshot.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = listOfNotNull(
                                    snapshot.artist.takeIf { it.isNotBlank() },
                                    snapshot.volumePercent.takeIf { it >= 0 }?.let { "Vol $it%" }
                                ).joinToString(" · ").ifBlank { snapshot.sourceDeviceName },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinkStatusPill(
                            label = if (snapshot.isPlaying) "Playing" else "Paused",
                            active = snapshot.isPlaying
                        )
                    }
                }
            }

            val targetId = selectedPeer?.deviceId
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { targetId?.let { onSendMediaCommand(it, "PREV") } },
                    enabled = targetId != null,
                    modifier = Modifier.testTag("media_prev_button")
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous Track")
                }
                FilledTonalButton(
                    onClick = { targetId?.let { onSendMediaCommand(it, "PLAY_PAUSE") } },
                    enabled = targetId != null,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("media_play_pause_button")
                ) {
                    Icon(
                        if (snapshot?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play or Pause"
                    )
                }
                IconButton(
                    onClick = { targetId?.let { onSendMediaCommand(it, "NEXT") } },
                    enabled = targetId != null,
                    modifier = Modifier.testTag("media_next_button")
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next Track")
                }
                IconButton(
                    onClick = { targetId?.let { onSendMediaCommand(it, "VOL_DOWN") } },
                    enabled = targetId != null,
                    modifier = Modifier.testTag("media_vol_down_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Volume Down")
                }
                IconButton(
                    onClick = { targetId?.let { onSendMediaCommand(it, "VOL_UP") } },
                    enabled = targetId != null,
                    modifier = Modifier.testTag("media_vol_up_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume Up")
                }
                IconButton(
                    onClick = { targetId?.let { onSendMediaCommand(it, "MUTE") } },
                    enabled = targetId != null,
                    modifier = Modifier.testTag("media_mute_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeMute, contentDescription = "Mute")
                }
            }

            OutlinedButton(
                onClick = { targetId?.let { onLocateDevice(it) } },
                enabled = targetId != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("media_locate_button")
            ) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ring Locate Chime on ${selectedPeer?.displayName ?: "Device"}")
            }
        }
    }
}

@Composable
fun H264DecoderSurfaceBox(
    modifier: Modifier = Modifier,
    onAttachSurface: (android.view.Surface?) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onAttachSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        onAttachSurface(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onAttachSurface(null)
                    }
                })
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiControlDockCard(
    connectedPeers: List<ConnectedPeerState>,
    multiControlState: MultiControlUiState,
    accessibilityConnected: Boolean,
    onSetReceiverArmed: (Boolean) -> Unit,
    onSendTap: (String, Float, Float) -> Unit,
    onSendSwipe: (String, Float, Float, Float, Float) -> Unit,
    onSendNav: (String, String) -> Unit,
    onSendText: (String, String) -> Unit
) {
    val context = LocalContext.current
    var selectedTargetId by remember(connectedPeers) {
        mutableStateOf(connectedPeers.firstOrNull()?.deviceId)
    }
    val selectedPeer = connectedPeers.firstOrNull { it.deviceId == selectedTargetId }
        ?: connectedPeers.firstOrNull()
    var textDraft by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Multi-Control Input Sharing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = multiControlState.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinkStatusPill(
                    label = if (accessibilityConnected) "Accessibility On" else "View-Only",
                    active = accessibilityConnected && multiControlState.receiverArmed
                )
            }

            // Receiver Arming & Accessibility Status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (multiControlState.activeRemoteControllerName != null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Allow Remote Input on This Device", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (accessibilityConnected) {
                                        "Opt-in Accessibility gesture bridge connected"
                                    } else {
                                        "Enable LINK Accessibility Service to accept taps & keys"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = multiControlState.receiverArmed,
                            onCheckedChange = onSetReceiverArmed,
                            modifier = Modifier.testTag("input_receiver_switch")
                        )
                    }

                    if (!accessibilityConnected) {
                        OutlinedButton(
                            onClick = { PermissionHelper.openAccessibilitySettings(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("open_accessibility_settings_button")
                        ) {
                            Text("Enable Accessibility Input Service")
                        }
                    }

                    if (multiControlState.receiverArmed || multiControlState.activeRemoteControllerName != null) {
                        Button(
                            onClick = { onSetReceiverArmed(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("emergency_disarm_input_button")
                        ) {
                            Icon(Icons.Filled.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = multiControlState.activeRemoteControllerName?.let {
                                    "Stop Remote Control ($it)"
                                } ?: "Disarm Remote Input Receiver"
                            )
                        }
                    }
                }
            }

            // Controller Section
            if (connectedPeers.isEmpty()) {
                Text(
                    text = "Connect a paired device to use the remote touchpad, navigation bar, and virtual keyboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Control Target Device", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    connectedPeers.forEach { peer ->
                        FilterChip(
                            selected = selectedPeer?.deviceId == peer.deviceId,
                            onClick = { selectedTargetId = peer.deviceId },
                            label = { Text(peer.displayName) }
                        )
                    }
                }
            }

            val targetId = selectedPeer?.deviceId
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .testTag("multicontrol_touchpad")
                    .pointerInput(targetId) {
                        if (targetId == null) return@pointerInput
                        detectTapGestures { offset ->
                            val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            onSendTap(targetId, nx, ny)
                        }
                    }
                    .pointerInput(targetId) {
                        if (targetId == null) return@pointerInput
                        var startX = 0f
                        var startY = 0f
                        var endX = 0f
                        var endY = 0f
                        detectDragGestures(
                            onDragStart = { start ->
                                startX = (start.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                startY = (start.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                endX = startX
                                endY = startY
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                endX = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                endY = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                onSendSwipe(targetId, startX, startY, endX, endY)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.TouchApp,
                        contentDescription = "Remote Touchpad Surface",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = selectedPeer?.let { "Tap or drag to control ${it.displayName}" }
                            ?: "Touchpad ready when a device connects",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { targetId?.let { onSendNav(it, "BACK") } },
                    enabled = targetId != null,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("remote_nav_back")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Back")
                }
                OutlinedButton(
                    onClick = { targetId?.let { onSendNav(it, "HOME") } },
                    enabled = targetId != null,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("remote_nav_home")
                ) {
                    Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Home")
                }
                OutlinedButton(
                    onClick = { targetId?.let { onSendNav(it, "RECENTS") } },
                    enabled = targetId != null,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("remote_nav_recents")
                ) {
                    Icon(Icons.Filled.RecentActors, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Recents")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    label = { Text("Send keyboard text to remote device") },
                    leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("remote_text_input")
                )
                Button(
                    onClick = {
                        val id = targetId ?: return@Button
                        if (textDraft.isNotBlank()) {
                            onSendText(id, textDraft)
                            textDraft = ""
                        }
                    },
                    enabled = targetId != null && textDraft.isNotBlank(),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("remote_text_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Remote Text")
                }
            }
        }
    }
}
