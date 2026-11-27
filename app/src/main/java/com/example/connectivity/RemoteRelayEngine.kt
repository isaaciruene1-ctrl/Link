package com.example.connectivity

import android.util.Log
import com.example.data.cloud.CloudEcosystemRepository
import com.example.data.cloud.RelaySignalDoc
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.LinkPreferencesRepository
import com.example.data.local.TrustedDeviceDao
import com.example.security.DeviceIdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class RelayChannelUiState(
    val activeSignalsCount: Int = 0,
    val lastSignalingEvent: String = "Cloud relay standby",
    val lastRouteUsed: TransportRoute = TransportRoute.LOCAL_WIFI
)

class RemoteRelayEngine(
    private val connectivityEngine: ConnectivityManagerEngine,
    private val identityManager: DeviceIdentityManager,
    private val trustedDeviceDao: TrustedDeviceDao,
    private val activityLogDao: ActivityLogDao,
    private val preferencesRepository: LinkPreferencesRepository,
    private val cloudRepository: CloudEcosystemRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var signalObserverJob: Job? = null
    private val processedSignalIds = HashSet<String>()

    private val _state = MutableStateFlow(RelayChannelUiState())
    val state: StateFlow<RelayChannelUiState> = _state.asStateFlow()

    fun startObservingSignals(userId: String) {
        if (userId.isBlank()) return
        signalObserverJob?.cancel()
        val localId = identityManager.getOrCreateIdentity().deviceId
        signalObserverJob = scope.launch {
            cloudRepository.observeRelaySignals(userId)
                .catch { e ->
                    Log.w(TAG, "Relay signal observer paused: ${e.localizedMessage}")
                }
                .collect { signals ->
                    val incoming = signals.filter {
                        !it.consumed &&
                            (it.targetDeviceId == localId || it.targetDeviceId == "ALL") &&
                            it.senderDeviceId != localId
                    }
                    _state.value = _state.value.copy(activeSignalsCount = incoming.size)
                    incoming.forEach { signal ->
                        if (processedSignalIds.add(signal.id)) {
                            handleIncomingSignal(signal)
                        }
                    }
                }
        }
    }

    fun stopObserving() {
        signalObserverJob?.cancel()
        signalObserverJob = null
    }

    suspend fun sendSmartControlMessage(
        targetDeviceId: String,
        type: String,
        payload: JSONObject
    ): TransportRoute? {
        // 1. Always prefer direct encrypted LAN / Wi-Fi Direct socket
        if (connectivityEngine.sendControlMessage(targetDeviceId, type, payload)) {
            _state.value = _state.value.copy(lastRouteUsed = TransportRoute.LOCAL_WIFI)
            return TransportRoute.LOCAL_WIFI
        }

        // 2. Fallback to End-to-End Encrypted Cloud Relay when opted in
        val prefs = preferencesRepository.preferencesFlow.first()
        if (!prefs.cloudWorkspaceSyncOptIn) return null
        val trusted = trustedDeviceDao.getById(targetDeviceId) ?: return null
        if (!trusted.isTrusted || trusted.relaySecretBase64.isBlank()) return null

        val localId = identityManager.getOrCreateIdentity().deviceId
        val roomId = buildRoomId(localId, targetDeviceId)
        val envelope = JSONObject().apply {
            put("type", type)
            put("payload", payload)
            put("ts", System.currentTimeMillis())
        }.toString()

        val encrypted = identityManager.encryptRelayPayload(
            relaySecretBase64 = trusted.relaySecretBase64,
            plaintext = envelope,
            aadText = "$roomId|$localId|$targetDeviceId"
        )

        val res = cloudRepository.sendRelaySignal(
            roomId = roomId,
            senderDeviceId = localId,
            senderDeviceName = prefs.deviceDisplayName,
            targetDeviceId = targetDeviceId,
            signalType = "ENCRYPTED_RELAY_PACKET",
            payload = encrypted
        )
        if (res.isSuccess) {
            _state.value = _state.value.copy(
                lastRouteUsed = TransportRoute.CLOUD_RELAY,
                lastSignalingEvent = "Sent encrypted $type via Cloud Relay to ${trusted.displayName}"
            )
            return TransportRoute.CLOUD_RELAY
        }
        return null
    }

    fun initiateWebRtcSignalingOffer(targetDeviceId: String, localHost: String?, localPort: Int) {
        scope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            val localId = identityManager.getOrCreateIdentity().deviceId
            val roomId = buildRoomId(localId, targetDeviceId)
            val sdpOfferJson = JSONObject().apply {
                put("protocol", "LINK-ICE-V1")
                put("host", localHost ?: "")
                put("port", localPort)
                put("iceRole", "controlling")
                put("nonce", identityManager.randomNonce(8))
            }.toString()
            val res = cloudRepository.sendRelaySignal(
                roomId = roomId,
                senderDeviceId = localId,
                senderDeviceName = prefs.deviceDisplayName,
                targetDeviceId = targetDeviceId,
                signalType = "OFFER",
                payload = sdpOfferJson
            )
            _state.value = _state.value.copy(
                lastSignalingEvent = if (res.isSuccess) {
                    "Sent ICE/SDP offer to $targetDeviceId"
                } else {
                    "Signaling offer failed: ${res.exceptionOrNull()?.localizedMessage}"
                }
            )
        }
    }

    fun sendWakePing(targetDeviceId: String) {
        scope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            val localId = identityManager.getOrCreateIdentity().deviceId
            val roomId = buildRoomId(localId, targetDeviceId)
            val res = cloudRepository.sendRelaySignal(
                roomId = roomId,
                senderDeviceId = localId,
                senderDeviceName = prefs.deviceDisplayName,
                targetDeviceId = targetDeviceId,
                signalType = "WAKE_PING",
                payload = JSONObject().put("ts", System.currentTimeMillis()).toString()
            )
            _state.value = _state.value.copy(
                lastSignalingEvent = if (res.isSuccess) "Sent remote wake ping" else "Wake ping failed"
            )
        }
    }

    private suspend fun handleIncomingSignal(signal: RelaySignalDoc) {
        val localId = identityManager.getOrCreateIdentity().deviceId
        when (signal.signalType) {
            "OFFER" -> {
                val json = runCatching { JSONObject(signal.payload) }.getOrNull()
                val host = json?.optString("host").orEmpty()
                val port = json?.optInt("port", 0) ?: 0
                if (host.isNotBlank() && port > 0) {
                    connectivityEngine.connectToDirectHost(host, port)
                }
                val prefs = preferencesRepository.preferencesFlow.first()
                cloudRepository.sendRelaySignal(
                    roomId = signal.roomId,
                    senderDeviceId = localId,
                    senderDeviceName = prefs.deviceDisplayName,
                    targetDeviceId = signal.senderDeviceId,
                    signalType = "ANSWER",
                    payload = JSONObject().apply {
                        put("accepted", true)
                        put("port", connectivityEngine.localPort.value)
                    }.toString()
                )
                _state.value = _state.value.copy(
                    lastSignalingEvent = "Processed SDP/ICE offer from ${signal.senderDeviceName}"
                )
            }
            "ANSWER", "ICE_CANDIDATE" -> {
                _state.value = _state.value.copy(
                    lastSignalingEvent = "Received ${signal.signalType} from ${signal.senderDeviceName}"
                )
            }
            "WAKE_PING" -> {
                connectivityEngine.triggerManualScan()
                _state.value = _state.value.copy(
                    lastSignalingEvent = "Wake ping from ${signal.senderDeviceName} — refreshed discovery"
                )
            }
            "ENCRYPTED_RELAY_PACKET" -> {
                val trusted = trustedDeviceDao.getById(signal.senderDeviceId)
                if (trusted != null && trusted.isTrusted && trusted.relaySecretBase64.isNotBlank()) {
                    runCatching {
                        val plain = identityManager.decryptRelayPayload(
                            relaySecretBase64 = trusted.relaySecretBase64,
                            encodedEnvelope = signal.payload,
                            aadText = "${signal.roomId}|${signal.senderDeviceId}|$localId"
                        )
                        val envelope = JSONObject(plain)
                        val type = envelope.optString("type")
                        val body = envelope.optJSONObject("payload") ?: JSONObject()
                        connectivityEngine.dispatchRelayedControlEvent(
                            IncomingControlEvent(
                                peerDeviceId = signal.senderDeviceId,
                                peerName = signal.senderDeviceName,
                                type = type,
                                payload = body
                            )
                        )
                        activityLogDao.insert(
                            ActivityLogEntity(
                                logId = UUID.randomUUID().toString(),
                                category = "RELAY",
                                deviceId = signal.senderDeviceId,
                                summary = "Decrypted E2EE relay $type from ${signal.senderDeviceName}"
                            )
                        )
                    }.onFailure {
                        Log.w(TAG, "Rejected invalid E2EE relay packet from ${signal.senderDeviceId}: ${it.localizedMessage}")
                    }
                }
            }
        }
        cloudRepository.markRelaySignalConsumed(signal.id)
    }

    private fun buildRoomId(a: String, b: String): String =
        "room_" + listOf(a, b).sorted().joinToString("_").take(96)

    companion object {
        private const val TAG = "RemoteRelayEngine"
    }
}
