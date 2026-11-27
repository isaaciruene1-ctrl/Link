package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.connectivity.ConnectedPeerState
import com.example.data.local.TransferRecordEntity
import com.example.transfer.ActiveTransferProgress
import com.example.transfer.FileTransferEngine
import com.example.ui.components.LinkEmptyStateCard
import com.example.ui.components.LinkSectionHeader
import com.example.ui.components.LinkStatusPill

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferScreen(
    connectedPeers: List<ConnectedPeerState>,
    activeTransfers: Map<String, ActiveTransferProgress>,
    transferHistory: List<TransferRecordEntity>,
    onSendFiles: (List<Uri>, List<String>) -> Unit,
    onPauseTransfer: (String) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onRetryTransfer: (TransferRecordEntity) -> Unit,
    onClearHistory: () -> Unit
) {
    var selectedTargetIds by remember(connectedPeers) {
        mutableStateOf(connectedPeers.firstOrNull()?.let { setOf(it.deviceId) } ?: emptySet())
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty() && selectedTargetIds.isNotEmpty()) {
            onSendFiles(uris, selectedTargetIds.toList())
        }
    }

    val safDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty() && selectedTargetIds.isNotEmpty()) {
            onSendFiles(uris, selectedTargetIds.toList())
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            LinkSectionHeader(
                title = "Encrypted LAN File Transfer",
                subtitle = "Zero-permission Photo Picker & Storage Access Framework with 64 KB chunked streaming and SHA-256 verification"
            )
        }

        // Target Selector & Pickers
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Target Devices", style = MaterialTheme.typography.titleSmall)
                    if (connectedPeers.isEmpty()) {
                        Text(
                            text = "Connect at least one device on Wi-Fi from the Home tab to send files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            connectedPeers.forEach { peer ->
                                val selected = selectedTargetIds.contains(peer.deviceId)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedTargetIds = if (selected) {
                                            selectedTargetIds - peer.deviceId
                                        } else {
                                            selectedTargetIds + peer.deviceId
                                        }
                                    },
                                    label = { Text(peer.displayName) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            enabled = selectedTargetIds.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("pick_media_button")
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Photos / Video")
                        }
                        OutlinedButton(
                            onClick = { safDocumentLauncher.launch(arrayOf("*/*")) },
                            enabled = selectedTargetIds.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("pick_documents_button")
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Files / APKs")
                        }
                    }
                }
            }
        }

        // Active Transfers
        if (activeTransfers.isNotEmpty()) {
            item { LinkSectionHeader(title = "Active Transfers") }
            items(activeTransfers.values.toList(), key = { it.transferId }) { progress ->
                val ratio = if (progress.totalBytes > 0) {
                    (progress.bytesTransferred.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(progress.fileName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${progress.direction} · ${progress.peerName} · ${FileTransferEngine.formatBytes(progress.speedBytesPerSec)}/s · ETA ${progress.etaSeconds}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                if (progress.direction == "OUTGOING") {
                                    IconButton(onClick = { onPauseTransfer(progress.transferId) }) {
                                        Icon(Icons.Filled.Pause, contentDescription = "Pause Transfer")
                                    }
                                }
                                IconButton(onClick = { onCancelTransfer(progress.transferId) }) {
                                    Icon(Icons.Filled.Cancel, contentDescription = "Cancel Transfer")
                                }
                            }
                        }
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${FileTransferEngine.formatBytes(progress.bytesTransferred)} / ${FileTransferEngine.formatBytes(progress.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // Transfer History
        item {
            LinkSectionHeader(
                title = "Transfer History",
                subtitle = "Incoming files save to Android/data/.../Downloads/LINK",
                trailing = {
                    if (transferHistory.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) { Text("Clear") }
                    }
                }
            )
        }

        if (transferHistory.isEmpty()) {
            item {
                LinkEmptyStateCard(
                    icon = Icons.Filled.SwapHoriz,
                    title = "No Transfers Recorded",
                    description = "Select photos, videos, documents, or APKs to stream directly to connected LINK devices."
                )
            }
        } else {
            items(transferHistory, key = { it.transferId }) { record ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(record.fileName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${record.direction} · ${record.peerDeviceName} · ${FileTransferEngine.formatBytes(record.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (record.sha256Checksum.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (record.checksumVerified) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = "SHA-256 Verified",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        text = "SHA-256 ${record.sha256Checksum.take(12)}...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (!record.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = record.errorMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (record.status == "FAILED" || record.status == "PAUSED") {
                                IconButton(onClick = { onRetryTransfer(record) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Retry Transfer")
                                }
                            }
                            LinkStatusPill(
                                label = record.status,
                                active = record.status == "COMPLETED"
                            )
                        }
                    }
                }
            }
        }
    }
}
