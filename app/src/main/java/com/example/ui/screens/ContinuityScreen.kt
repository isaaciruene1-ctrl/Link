package com.example.ui.screens

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.connectivity.ConnectedPeerState
import com.example.continuity.LocalHandoffBanner
import com.example.continuity.NowPlayingSnapshot
import com.example.core.PermissionHelper
import com.example.data.cloud.CloudHandoffDoc
import com.example.data.cloud.SharedWorkspaceDoc
import com.example.data.local.ClipboardEntryEntity
import com.example.data.local.LinkPreferences
import com.example.data.local.SyncedNotificationEntity
import com.example.ui.CloudUiState
import com.example.ui.components.LinkEmptyStateCard
import com.example.ui.components.LinkSectionHeader
import com.example.ui.components.LinkStatusPill
import com.example.ui.components.MediaRemoteCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContinuityScreen(
    preferences: LinkPreferences,
    connectedPeers: List<ConnectedPeerState>,
    clipboardEntries: List<ClipboardEntryEntity>,
    clipboardStatus: String,
    syncedNotifications: List<SyncedNotificationEntity>,
    localHandoffs: List<LocalHandoffBanner>,
    cloudHandoffs: CloudUiState<List<CloudHandoffDoc>>,
    cloudWorkspaces: CloudUiState<List<SharedWorkspaceDoc>>,
    remoteNowPlaying: NowPlayingSnapshot?,
    onToggleClipboardOptIn: (Boolean) -> Unit,
    onSendClipboard: (List<String>, String?) -> Unit,
    onCopyClipboard: (String) -> Unit,
    onClearClipboard: () -> Unit,
    onSendHandoff: (String?, String, String, String) -> Unit,
    onContinueHandoff: (String, String, String?, String?) -> Unit,
    onDismissHandoff: (String?, String?) -> Unit,
    onMarkNotificationRead: (String) -> Unit,
    onMarkAllNotificationsRead: () -> Unit,
    onDismissNotification: (SyncedNotificationEntity) -> Unit,
    onSendMediaCommand: (String, String) -> Unit,
    onRefreshMediaState: (String) -> Unit,
    onLocateDevice: (String) -> Unit,
    onCreateWorkspace: (String, String, String?, List<String>) -> Unit,
    onDeleteWorkspace: (String) -> Unit
) {
    val context = LocalContext.current
    val notifAccessGranted = PermissionHelper.isNotificationListenerEnabled(context)

    var manualClipText by remember { mutableStateOf("") }
    var handoffUrl by remember { mutableStateOf("") }
    var showCreateWorkspace by remember { mutableStateOf(false) }
    var selectedNotifAppFilter by remember { mutableStateOf("All") }

    val availableApps = remember(syncedNotifications) {
        listOf("All") + syncedNotifications.map { it.appName }.distinct()
    }
    val filteredNotifications = remember(syncedNotifications, selectedNotifAppFilter) {
        if (selectedNotifAppFilter == "All") syncedNotifications
        else syncedNotifications.filter { it.appName == selectedNotifAppFilter }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Smart Handoffs
        item {
            LinkSectionHeader(
                title = "Smart Handoff",
                subtitle = "Continue links, notes, and workspaces on another device"
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = handoffUrl,
                    onValueChange = { handoffUrl = it },
                    label = { Text("Share URL or link to devices") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("handoff_url_input")
                )
                Button(
                    onClick = {
                        onSendHandoff(
                            connectedPeers.firstOrNull()?.deviceId,
                            "OPEN_URL",
                            "Continue on ${connectedPeers.firstOrNull()?.displayName ?: "My Devices"}",
                            handoffUrl
                        )
                        handoffUrl = ""
                    },
                    enabled = handoffUrl.isNotBlank(),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("send_handoff_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Handoff")
                }
            }
        }

        items(localHandoffs, key = { it.id }) { banner ->
            HandoffBannerCard(
                sourceName = banner.sourceDeviceName,
                title = banner.title,
                payload = banner.payload,
                onContinue = { onContinueHandoff(banner.actionType, banner.payload, null, banner.id) },
                onDismiss = { onDismissHandoff(null, banner.id) }
            )
        }

        if (cloudHandoffs is CloudUiState.Success) {
            val activeCloudHandoffs = cloudHandoffs.data.filter { !it.consumed }
            items(activeCloudHandoffs, key = { it.id }) { doc ->
                HandoffBannerCard(
                    sourceName = doc.sourceDeviceName,
                    title = doc.title,
                    payload = doc.payload,
                    onContinue = { onContinueHandoff(doc.actionType, doc.payload, doc.id, null) },
                    onDismiss = { onDismissHandoff(doc.id, null) }
                )
            }
        }

        // Universal Clipboard
        item {
            LinkSectionHeader(
                title = "Universal Clipboard",
                subtitle = clipboardStatus,
                trailing = {
                    Switch(
                        checked = preferences.clipboardSyncOptIn,
                        onCheckedChange = onToggleClipboardOptIn,
                        modifier = Modifier.testTag("clipboard_optin_switch")
                    )
                }
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Android restricts background clipboard reading. Use foreground send below; OTP codes and passwords are filtered automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualClipText,
                        onValueChange = { manualClipText = it },
                        label = { Text("Optional text or URL (leave blank to read current clipboard)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("clipboard_manual_input"),
                        maxLines = 2
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val targets = connectedPeers.map { it.deviceId }
                                onSendClipboard(targets, manualClipText.ifBlank { null })
                                manualClipText = ""
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("send_clipboard_button")
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Send Clipboard")
                        }
                        OutlinedButton(
                            onClick = onClearClipboard,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        items(clipboardEntries.take(6), key = { it.entryId }) { entry ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.contentPreview,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${entry.sourceDeviceName} · ${entry.contentType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (!entry.isSensitiveRedacted && entry.fullText.isNotBlank()) {
                        IconButton(onClick = { onCopyClipboard(entry.fullText) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy to Clipboard")
                        }
                    }
                }
            }
        }

        // Media Continuity & Remote Control
        item {
            MediaRemoteCard(
                connectedPeers = connectedPeers,
                remoteNowPlaying = remoteNowPlaying,
                onSendMediaCommand = onSendMediaCommand,
                onRefreshMediaState = onRefreshMediaState,
                onLocateDevice = onLocateDevice
            )
        }

        // Notification Sync Inbox
        item {
            LinkSectionHeader(
                title = "Notification Inbox",
                subtitle = if (notifAccessGranted) "NotificationListener active" else "Requires Notification Access in Android Settings",
                trailing = {
                    if (syncedNotifications.isNotEmpty()) {
                        TextButton(onClick = onMarkAllNotificationsRead) {
                            Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Read All")
                        }
                    }
                }
            )
            if (!notifAccessGranted) {
                LinkEmptyStateCard(
                    icon = Icons.Filled.NotificationsOff,
                    title = "Notification Access Not Enabled",
                    description = "LINK never bypasses Android security. Grant Notification Access in Settings to mirror notifications across your trusted devices.",
                    actionLabel = "Open Notification Settings",
                    onAction = { PermissionHelper.openNotificationListenerSettings(context) },
                    testTag = "open_notification_listener_settings"
                )
            } else if (availableApps.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableApps.forEach { app ->
                        FilterChip(
                            selected = selectedNotifAppFilter == app,
                            onClick = { selectedNotifAppFilter = app },
                            label = { Text(app) }
                        )
                    }
                }
            }
        }

        items(filteredNotifications.take(15), key = { it.notificationKey }) { notif ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (notif.isRead) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${notif.appName} · ${notif.sourceDeviceName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(notif.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = notif.bodyPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row {
                        if (!notif.isRead) {
                            TextButton(onClick = { onMarkNotificationRead(notif.notificationKey) }) {
                                Text("Read")
                            }
                        }
                        IconButton(onClick = { onDismissNotification(notif) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss Notification")
                        }
                    }
                }
            }
        }

        // Shared Workspaces (Firestore)
        item {
            LinkSectionHeader(
                title = "Shared Workspaces",
                subtitle = "Synchronized notes, tasks, and pinned links across your account",
                trailing = {
                    FilledTonalButton(
                        onClick = { showCreateWorkspace = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("create_workspace_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }
                }
            )
        }

        when (cloudWorkspaces) {
            CloudUiState.Loading -> {
                item {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Syncing shared workspaces...")
                    }
                }
            }
            is CloudUiState.Error -> {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            text = cloudWorkspaces.message,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            is CloudUiState.Success -> {
                if (cloudWorkspaces.data.isEmpty()) {
                    item {
                        LinkEmptyStateCard(
                            icon = Icons.Filled.Workspaces,
                            title = "No Shared Workspaces",
                            description = "Create a study, travel, or development workspace to continue tasks seamlessly between your phone and tablet.",
                            actionLabel = "Create Workspace",
                            onAction = { showCreateWorkspace = true },
                            testTag = "empty_create_workspace_button"
                        )
                    }
                } else {
                    items(cloudWorkspaces.data, key = { it.id }) { ws ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
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
                                    Text(ws.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    IconButton(onClick = { onDeleteWorkspace(ws.id) }) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Workspace")
                                    }
                                }
                                if (ws.notes.isNotBlank()) {
                                    Text(ws.notes, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (!ws.pinnedLink.isNullOrBlank()) {
                                    TextButton(
                                        onClick = { onContinueHandoff("OPEN_URL", ws.pinnedLink, null, null) }
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(ws.pinnedLink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (ws.tasks.isNotEmpty()) {
                                    ws.tasks.forEach { task ->
                                        Text("• $task", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                LinkStatusPill(label = "Updated from ${ws.activeDeviceName.ifBlank { "Device" }}", active = true)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateWorkspace) {
        CreateWorkspaceDialog(
            onDismiss = { showCreateWorkspace = false },
            onCreate = { title, notes, link, tasks ->
                onCreateWorkspace(title, notes, link, tasks)
                showCreateWorkspace = false
            }
        )
    }
}

@Composable
private fun HandoffBannerCard(
    sourceName: String,
    title: String,
    payload: String,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Handoff from $sourceName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(payload, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text("Continue")
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String?, List<String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var tasksRaw by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Shared Workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Workspace Title") },
                    singleLine = true,
                    modifier = Modifier.testTag("workspace_title_input")
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Shared Notes") },
                    maxLines = 3
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Pinned Link (optional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = tasksRaw,
                    onValueChange = { tasksRaw = it },
                    label = { Text("Tasks (comma separated)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tasks = tasksRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onCreate(title, notes, link.ifBlank { null }, tasks)
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("confirm_create_workspace_button")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
