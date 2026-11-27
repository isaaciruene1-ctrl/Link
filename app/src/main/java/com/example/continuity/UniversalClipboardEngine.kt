package com.example.continuity

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.example.connectivity.ConnectivityManagerEngine
import com.example.data.cloud.CloudEcosystemRepository
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.ClipboardEntryDao
import com.example.data.local.ClipboardEntryEntity
import com.example.data.local.LinkPreferencesRepository
import com.example.data.local.TrustedDeviceDao
import com.example.security.DeviceIdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

object SensitiveDataDetector {
    private val otpRegex = Regex("""\b\d{6,8}\b""")
    private val otpKeywordRegex = Regex("""\b(otp|code|verification|2fa|mfa|pin|token|password|passwd|secret)\b""", RegexOption.IGNORE_CASE)
    private val apiKeyRegex = Regex("""(AIza[0-9A-Za-z\-_]{20,}|sk-[0-9A-Za-z]{16,}|Bearer\s+[A-Za-z0-9\-._~+/]+=*)""")
    private val cardRegex = Regex("""\b(?:\d[ -]*?){13,16}\b""")

    fun isSensitiveText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (apiKeyRegex.containsMatchIn(trimmed)) return true
        if (otpKeywordRegex.containsMatchIn(trimmed) && otpRegex.containsMatchIn(trimmed)) return true
        if (trimmed.contains("password=", ignoreCase = true) || trimmed.contains("secret=", ignoreCase = true)) return true
        if (cardRegex.containsMatchIn(trimmed) && trimmed.count { it.isDigit() } in 13..16) return true
        return false
    }
}

class UniversalClipboardEngine(
    private val context: Context,
    private val connectivityEngine: ConnectivityManagerEngine,
    private val identityManager: DeviceIdentityManager,
    private val clipboardDao: ClipboardEntryDao,
    private val trustedDeviceDao: TrustedDeviceDao,
    private val activityLogDao: ActivityLogDao,
    private val preferencesRepository: LinkPreferencesRepository,
    private val cloudRepository: CloudEcosystemRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clipboardManager: ClipboardManager? by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    private val _statusMessage = MutableStateFlow("Foreground clipboard sync ready (opt-in)")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    val recentEntries: Flow<List<ClipboardEntryEntity>> = clipboardDao.observeActiveEntries()

    init {
        scope.launch {
            clipboardDao.pruneExpired()
            connectivityEngine.controlEvents.collect { event ->
                if (event.type == "CLIPBOARD_PUSH") {
                    handleIncomingClipboard(event.peerDeviceId, event.peerName, event.payload)
                }
            }
        }
    }

    fun readForegroundClipboardPreview(): String? {
        val clip = clipboardManager?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isSensitiveFlag = clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            if (isSensitiveFlag) return null
        }
        return clip.getItemAt(0).coerceToText(context)?.toString()?.take(2000)
    }

    fun sendCurrentClipboardToDevices(
        targetDeviceIds: List<String>,
        overrideText: String? = null
    ) {
        scope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            val text = overrideText?.trim()?.take(2000) ?: readForegroundClipboardPreview()?.trim()
            if (text.isNullOrBlank()) {
                _statusMessage.value = "Clipboard is empty or marked sensitive by Android."
                return@launch
            }
            if (prefs.clipboardSensitiveFilter && SensitiveDataDetector.isSensitiveText(text)) {
                _statusMessage.value = "Blocked: sensitive password, key, or OTP pattern detected."
                clipboardDao.insert(
                    ClipboardEntryEntity(
                        entryId = UUID.randomUUID().toString(),
                        sourceDeviceId = identityManager.getOrCreateIdentity().deviceId,
                        sourceDeviceName = prefs.deviceDisplayName,
                        contentPreview = "Redacted sensitive item",
                        fullText = "",
                        contentType = "TEXT",
                        isSensitiveRedacted = true
                    )
                )
                return@launch
            }

            val contentType = if (text.startsWith("http://") || text.startsWith("https://")) "URL" else "TEXT"
            val localId = identityManager.getOrCreateIdentity().deviceId
            clipboardDao.insert(
                ClipboardEntryEntity(
                    entryId = UUID.randomUUID().toString(),
                    sourceDeviceId = localId,
                    sourceDeviceName = prefs.deviceDisplayName,
                    contentPreview = text.take(120),
                    fullText = text,
                    contentType = contentType
                )
            )

            var sentLan = 0
            targetDeviceIds.forEach { targetId ->
                val ok = connectivityEngine.sendControlMessage(
                    targetId,
                    "CLIPBOARD_PUSH",
                    JSONObject().apply {
                        put("text", text)
                        put("contentType", contentType)
                    }
                )
                if (ok) sentLan++
            }

            if (sentLan == 0 && prefs.cloudWorkspaceSyncOptIn) {
                cloudRepository.publishHandoff(
                    sourceDeviceId = localId,
                    sourceDeviceName = prefs.deviceDisplayName,
                    targetDeviceId = targetDeviceIds.firstOrNull() ?: "ALL",
                    actionType = "CLIPBOARD_NOTE",
                    title = "Clipboard from ${prefs.deviceDisplayName}",
                    payload = text
                )
            }

            activityLogDao.insert(
                ActivityLogEntity(
                    logId = UUID.randomUUID().toString(),
                    category = "CLIPBOARD",
                    summary = "Shared clipboard ($contentType) to ${sentLan.coerceAtLeast(1)} target(s)"
                )
            )
            _statusMessage.value = if (sentLan > 0) {
                "Sent over encrypted Wi-Fi to $sentLan device(s)"
            } else {
                "Queued via cloud clipboard handoff"
            }
        }
    }

    fun copyToLocalClipboard(text: String) {
        val clip = ClipData.newPlainText("LINK Clipboard", text)
        clipboardManager?.setPrimaryClip(clip)
        _statusMessage.value = "Copied to local Android clipboard"
    }

    fun clearHistory() {
        scope.launch {
            clipboardDao.clearAll()
            _statusMessage.value = "Clipboard history cleared"
        }
    }

    private suspend fun handleIncomingClipboard(peerDeviceId: String, peerName: String, payload: JSONObject) {
        val prefs = preferencesRepository.preferencesFlow.first()
        if (!prefs.clipboardSyncOptIn) return
        val trusted = trustedDeviceDao.getById(peerDeviceId)
        if (trusted == null || !trusted.allowClipboardSync) return

        val text = payload.optString("text").trim().take(2000)
        if (text.isBlank()) return
        if (prefs.clipboardSensitiveFilter && SensitiveDataDetector.isSensitiveText(text)) return

        val contentType = payload.optString("contentType", "TEXT")
        clipboardDao.insert(
            ClipboardEntryEntity(
                entryId = UUID.randomUUID().toString(),
                sourceDeviceId = peerDeviceId,
                sourceDeviceName = peerName,
                contentPreview = text.take(120),
                fullText = text,
                contentType = contentType
            )
        )
        _statusMessage.value = "Received clipboard item from $peerName"
    }
}
