package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.view.Surface
import com.example.connectivity.ConnectedPeerState
import com.example.input.MultiControlUiState
import com.example.screens.LinkScreenShareService
import com.example.screens.ScreenSessionUiState
import com.example.screens.StreamCodecMode
import com.example.screens.StreamQualityPreset
import com.example.ui.components.H264DecoderSurfaceBox
import com.example.ui.components.LinkEmptyStateCard
import com.example.ui.components.LinkSectionHeader
import com.example.ui.components.LinkStatusPill
import com.example.ui.components.MultiControlDockCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreensScreen(
    screenState: ScreenSessionUiState,
    connectedPeers: List<ConnectedPeerState>,
    multiControlState: MultiControlUiState,
    accessibilityConnected: Boolean,
    onSelectQuality: (StreamQualityPreset) -> Unit,
    onSelectCodecMode: (StreamCodecMode) -> Unit,
    onAttachDecoderSurface: (Surface?) -> Unit,
    createConsentIntent: () -> Intent,
    onStartProjection: (Int, Intent, String, String) -> Unit,
    onStopProjection: () -> Unit,
    onSetInputReceiverArmed: (Boolean) -> Unit,
    onSendRemoteTap: (String, Float, Float) -> Unit,
    onSendRemoteSwipe: (String, Float, Float, Float, Float) -> Unit,
    onSendRemoteNav: (String, String) -> Unit,
    onSendRemoteText: (String, String) -> Unit
) {
    val context = LocalContext.current
    var selectedTargetId by remember(connectedPeers) {
        mutableStateOf(connectedPeers.firstOrNull()?.deviceId)
    }
    val selectedPeer = connectedPeers.firstOrNull { it.deviceId == selectedTargetId }
        ?: connectedPeers.firstOrNull()

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val target = selectedPeer
        if (result.resultCode == Activity.RESULT_OK && data != null && target != null) {
            LinkScreenShareService.start(context)
            onStartProjection(result.resultCode, data, target.deviceId, target.displayName)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            LinkSectionHeader(
                title = "Screen Mirroring",
                subtitle = screenState.statusText,
                trailing = {
                    LinkStatusPill(
                        label = when {
                            screenState.isBroadcasting -> "Streaming"
                            screenState.isReceivingH264 -> "H.264 Live"
                            screenState.incomingFrame != null -> "${screenState.incomingFramesCount} Frames"
                            else -> screenState.estimatedLatencyMs?.let { "${it}ms" } ?: "Idle"
                        },
                        active = screenState.isBroadcasting || screenState.incomingFrame != null || screenState.isReceivingH264
                    )
                }
            )
        }

        // Stream Configuration & Start/Stop Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Receiving Device", style = MaterialTheme.typography.titleSmall)
                    if (connectedPeers.isEmpty()) {
                        Text(
                            text = "No connected local devices available. Pair and connect a device over Wi-Fi on Home first.",
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

                    Text("Codec Pipeline", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamCodecMode.entries.forEach { mode ->
                            FilterChip(
                                selected = screenState.codecMode == mode,
                                onClick = { onSelectCodecMode(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("codec_${mode.name.lowercase()}")
                            )
                        }
                    }

                    Text("Stream Quality", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamQualityPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = screenState.quality == preset,
                                onClick = { onSelectQuality(preset) },
                                label = { Text(preset.label) }
                            )
                        }
                    }

                    if (screenState.isBroadcasting) {
                        Button(
                            onClick = onStopProjection,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("stop_mirroring_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.StopScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Stop Screen Mirroring")
                        }
                    } else {
                        Button(
                            onClick = { projectionLauncher.launch(createConsentIntent()) },
                            enabled = selectedPeer != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("start_mirroring_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Start Mirroring (MediaProjection)")
                        }
                    }
                }
            }
        }

        // Incoming Live Mirror Viewer (H.264 Hardware SurfaceView or Adaptive JPEG Bitmap)
        item {
            LinkSectionHeader(
                title = "Remote Screen Viewer",
                subtitle = when {
                    screenState.isReceivingH264 ->
                        "H.264 Hardware Stream (${screenState.incomingWidth}x${screenState.incomingHeight}) · ${screenState.incomingFramesCount} packets"
                    screenState.estimatedLatencyMs != null ->
                        "Adaptive LAN Stream · ~${screenState.estimatedLatencyMs}ms latency"
                    else -> "Incoming screen streams from paired devices appear here"
                }
            )
            Spacer(Modifier.size(8.dp))
            val frame = screenState.incomingFrame
            if (screenState.isReceivingH264) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    H264DecoderSurfaceBox(
                        modifier = Modifier.fillMaxSize(),
                        onAttachSurface = onAttachDecoderSurface
                    )
                }
            } else if (frame != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Mirrored remote device screen",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                LinkEmptyStateCard(
                    icon = Icons.Filled.Cast,
                    title = "No Active Incoming Stream",
                    description = "When a connected LINK device shares its screen with you, hardware H.264 or adaptive JPEG frames render live here."
                )
            }
        }

        // Phase 5: Multi-Control Touchpad & Virtual Keyboard
        item {
            MultiControlDockCard(
                connectedPeers = connectedPeers,
                multiControlState = multiControlState,
                accessibilityConnected = accessibilityConnected,
                onSetReceiverArmed = onSetInputReceiverArmed,
                onSendTap = onSendRemoteTap,
                onSendSwipe = onSendRemoteSwipe,
                onSendNav = onSendRemoteNav,
                onSendText = onSendRemoteText
            )
        }

        // Honest Android Capability & Security Notice
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Android Screen & Input Boundaries", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "• Protected apps (banking, DRM video) appear black per Android FLAG_SECURE.\n" +
                                "• H.264 MediaCodec requires MediaProjection consent; falls back cleanly to Adaptive JPEG if hardware codec is busy.\n" +
                                "• Remote touch & navigation require explicit Android Accessibility Service opt-in and receiver arming.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
