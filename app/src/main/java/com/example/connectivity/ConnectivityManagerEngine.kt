package com.example.connectivity

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.core.SystemTelemetryProvider
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.LinkPreferencesRepository
import com.example.data.local.TrustedDeviceDao
import com.example.data.local.TrustedDeviceEntity
import com.example.security.DeviceIdentityManager
import com.example.security.QrPairingPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class IncomingControlEvent(
    val peerDeviceId: String,
    val peerName: String,
    val type: String,
    val payload: JSONObject
)

data class IncomingBinaryEvent(
    val peerDeviceId: String,
    val frameType: WireFrameType,
    val bytes: ByteArray
)

class ConnectivityManagerEngine(
    private val context: Context,
    private val identityManager: DeviceIdentityManager,
    private val telemetryProvider: SystemTelemetryProvider,
    private val nsdEngine: NsdDiscoveryEngine,
    private val bleEngine: BleDiscoveryEngine,
    private val trustedDeviceDao: TrustedDeviceDao,
    private val activityLogDao: ActivityLogDao,
    private val preferencesRepository: LinkPreferencesRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var heartbeatJob: Job? = null

    private val activeSessions = ConcurrentHashMap<String, EncryptedPeerTransport>()
    private val connectedMap = ConcurrentHashMap<String, ConnectedPeerState>()
    private val handshakeAttemptsByIp = ConcurrentHashMap<String, MutableList<Long>>()
    private val pendingConfirmations = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _localPort = MutableStateFlow(0)
    val localPort: StateFlow<Int> = _localPort.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<ConnectedPeerState>>(emptyList())
    val connectedPeers: StateFlow<List<ConnectedPeerState>> = _connectedPeers.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _controlEvents = MutableSharedFlow<IncomingControlEvent>(extraBufferCapacity = 64)
    val controlEvents: SharedFlow<IncomingControlEvent> = _controlEvents.asSharedFlow()

    private val _binaryEvents = MutableSharedFlow<IncomingBinaryEvent>(extraBufferCapacity = 64)
    val binaryEvents: SharedFlow<IncomingBinaryEvent> = _binaryEvents.asSharedFlow()

    private val _diagnostics = MutableStateFlow("Transport server not started")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    fun startTransportAndDiscovery(enableBleIfPermitted: Boolean = true) {
        startServerSocketIfNeeded()
        val identity = identityManager.getOrCreateIdentity()
        val telemetry = telemetryProvider.currentSnapshot()
        scope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            val port = _localPort.value
            if (port > 0) {
                nsdEngine.startServiceRegistration(
                    deviceId = identity.deviceId,
                    displayName = prefs.deviceDisplayName,
                    manufacturer = telemetry.manufacturer,
                    model = telemetry.model,
                    deviceType = telemetry.formFactor.name,
                    port = port
                )
                if (prefs.autoDiscoveryEnabled) {
                    nsdEngine.startDiscovery(identity.deviceId)
                }
                if (enableBleIfPermitted && bleEngine.isBluetoothReady()) {
                    bleEngine.startBleScanAndAdvertise(identity.deviceId, prefs.deviceDisplayName)
                }
            }
            reconnectTrustedPeers()
        }
    }

    fun triggerManualScan() {
        startServerSocketIfNeeded()
        val identity = identityManager.getOrCreateIdentity()
        nsdEngine.startDiscovery(identity.deviceId)
        scope.launch {
            val prefs = preferencesRepository.preferencesFlow.first()
            if (bleEngine.isBluetoothReady()) {
                bleEngine.startBleScanAndAdvertise(identity.deviceId, prefs.deviceDisplayName)
            }
        }
    }

    private fun startServerSocketIfNeeded() {
        if (serverSocket != null && serverSocket?.isClosed == false) return
        scope.launch {
            runCatching {
                val ss = ServerSocket(0)
                serverSocket = ss
                _localPort.value = ss.localPort
                _diagnostics.value = "Listening on ${getLocalIpv4Address() ?: "LAN"}:${ss.localPort}"
                startAcceptLoop(ss)
                startHeartbeatLoop()
            }.onFailure {
                _diagnostics.value = "Server socket error: ${it.localizedMessage}"
                Log.e(TAG, "Failed to bind local server socket", it)
            }
        }
    }

    private fun startAcceptLoop(ss: ServerSocket) {
        acceptJob?.cancel()
        acceptJob = scope.launch {
            while (isActive && !ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                val ip = client.inetAddress.hostAddress ?: "unknown"
                if (isRateLimited(ip)) {
                    runCatching { client.close() }
                    continue
                }
                launch { handleIncomingSocket(client) }
            }
        }
    }

    private fun isRateLimited(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val history = handshakeAttemptsByIp.getOrPut(ip) { mutableListOf() }
        synchronized(history) {
            history.removeAll { now - it > 10_000L }
            if (history.size >= 8) return true
            history.add(now)
            return false
        }
    }

    fun buildLocalQrPayload(displayName: String): QrPairingPayload? {
        val port = _localPort.value
        val ip = getLocalIpv4Address() ?: return null
        if (port <= 0) return null
        val id = identityManager.getOrCreateIdentity()
        return QrPairingPayload(
            deviceId = id.deviceId,
            deviceName = displayName,
            host = ip,
            port = port,
            publicKeyBase64 = id.publicKeyBase64,
            nonce = identityManager.randomNonce(12)
        )
    }

    fun pairWithDiscoveredPeer(peer: DiscoveredPeer) {
        if (peer.host.isBlank() || peer.port <= 0) {
            _pairingState.value = PairingState.Failed(
                "Bluetooth LE beacon found (${peer.displayName}). Waiting for Wi-Fi LAN address or scan QR code."
            )
            return
        }
        initiateConnection(peer.host, peer.port, expectedDeviceId = peer.deviceId, expectedPubKey = peer.publicKeyBase64)
    }

    fun pairWithQrPayload(payload: QrPairingPayload) {
        val derivedId = identityManager.deriveDeviceIdFromPublicKey(payload.publicKeyBase64)
        if (derivedId != payload.deviceId) {
            _pairingState.value = PairingState.Failed("QR public key fingerprint mismatch.")
            return
        }
        initiateConnection(payload.host, payload.port, expectedDeviceId = derivedId, expectedPubKey = payload.publicKeyBase64)
    }

    fun confirmPendingPairing(sessionTempId: String, accepted: Boolean) {
        pendingConfirmations.remove(sessionTempId)?.complete(accepted)
        if (!accepted) {
            _pairingState.value = PairingState.Idle
        }
    }

    fun resetPairingState() {
        _pairingState.value = PairingState.Idle
    }

    fun connectToDirectHost(host: String, port: Int = 51721) {
        if (host.isBlank() || port <= 0) return
        initiateConnection(host, port, expectedDeviceId = null, expectedPubKey = null)
    }

    fun dispatchRelayedControlEvent(event: IncomingControlEvent) {
        _controlEvents.tryEmit(event)
    }

    private fun initiateConnection(
        host: String,
        port: Int,
        expectedDeviceId: String?,
        expectedPubKey: String?
    ) {
        scope.launch {
            _pairingState.value = PairingState.Handshaking(host)
            runCatching {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5_000)
                performHandshake(
                    socket = socket,
                    isInitiator = true,
                    expectedDeviceId = expectedDeviceId,
                    expectedPubKey = expectedPubKey
                )
            }.onFailure {
                Log.e(TAG, "Connection failed to $host:$port", it)
                _pairingState.value = PairingState.Failed(it.localizedMessage ?: "Unable to connect to $host:$port")
            }
        }
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        runCatching {
            performHandshake(socket = socket, isInitiator = false, expectedDeviceId = null, expectedPubKey = null)
        }.onFailure {
            Log.w(TAG, "Incoming handshake failed: ${it.localizedMessage}")
            runCatching { socket.close() }
        }
    }

    private suspend fun performHandshake(
        socket: Socket,
        isInitiator: Boolean,
        expectedDeviceId: String?,
        expectedPubKey: String?
    ) {
        socket.soTimeout = 15_000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        val identity = identityManager.getOrCreateIdentity()
        val telemetry = telemetryProvider.currentSnapshot()
        val prefs = preferencesRepository.preferencesFlow.first()
        val ephKeyPair = identityManager.generateEphemeralEcdhKeyPair()
        val ephPubB64 = Base64.encodeToString(ephKeyPair.public.encoded, Base64.NO_WRAP)
        val nonce = identityManager.randomNonce(16)
        val challengeData = "${identity.deviceId}|$ephPubB64|$nonce".toByteArray(Charsets.UTF_8)
        val signatureB64 = Base64.encodeToString(identityManager.signChallenge(challengeData), Base64.NO_WRAP)

        val helloJson = JSONObject().apply {
            put("deviceId", identity.deviceId)
            put("displayName", prefs.deviceDisplayName)
            put("manufacturer", telemetry.manufacturer)
            put("model", telemetry.model)
            put("deviceType", telemetry.formFactor.name)
            put("publicKey", identity.publicKeyBase64)
            put("ephemeralPub", ephPubB64)
            put("nonce", nonce)
            put("signature", signatureB64)
            put("listenPort", _localPort.value)
            put("battery", telemetry.batteryPercent)
            put("charging", telemetry.isCharging)
            put("networkType", telemetry.networkType.name)
        }

        if (isInitiator) {
            writePlainFrame(output, helloJson.toString())
        }
        val peerHello = JSONObject(readPlainFrame(input))
        if (!isInitiator) {
            writePlainFrame(output, helloJson.toString())
        }

        val peerPub = peerHello.getString("publicKey")
        val peerDeviceId = peerHello.getString("deviceId")
        val derivedPeerId = identityManager.deriveDeviceIdFromPublicKey(peerPub)
        if (derivedPeerId != peerDeviceId) {
            throw SecurityException("Peer deviceId does not match public key hash")
        }
        if (expectedDeviceId != null && expectedDeviceId != peerDeviceId) {
            throw SecurityException("Connected peer ID mismatch")
        }
        if (expectedPubKey != null && expectedPubKey != peerPub) {
            throw SecurityException("Pinned QR public key mismatch")
        }

        val peerEph = peerHello.getString("ephemeralPub")
        val peerNonce = peerHello.getString("nonce")
        val peerSig = Base64.decode(peerHello.getString("signature"), Base64.NO_WRAP)
        val peerChallenge = "$peerDeviceId|$peerEph|$peerNonce".toByteArray(Charsets.UTF_8)
        if (!identityManager.verifyPeerSignature(peerPub, peerChallenge, peerSig)) {
            throw SecurityException("Peer ECDSA signature verification failed")
        }

        val sasCode = identityManager.computeShortAuthCode(identity.deviceId, peerDeviceId, ephPubB64, peerEph)
        val peerName = peerHello.optString("displayName", "Android Device")
        val peerMfg = peerHello.optString("manufacturer", "Android")
        val peerModel = peerHello.optString("model", "Device")
        val peerType = peerHello.optString("deviceType", "PHONE")
        val peerHost = socket.inetAddress.hostAddress ?: ""
        val peerPort = peerHello.optInt("listenPort", socket.port)
        val peerFingerprint = peerDeviceId.removePrefix("lnk_").take(16).chunked(4).joinToString("-").uppercase()

        val existingTrusted = trustedDeviceDao.getById(peerDeviceId)
        val isTrusted = existingTrusted?.isTrusted == true

        val orderedIds = listOf(identity.deviceId, peerDeviceId).sorted()
        val orderedPubs = listOf(ephPubB64, peerEph).sorted()
        val transcript = (orderedIds + orderedPubs).joinToString("|").toByteArray(Charsets.UTF_8)
        val cipher = identityManager.deriveSessionCipher(ephKeyPair.private, peerEph, transcript)
        val relaySecret = identityManager.deriveRelaySecretFromSession(ephKeyPair.private, peerEph, orderedIds)

        if (!isTrusted) {
            val tempId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Boolean>()
            pendingConfirmations[tempId] = deferred

            _pairingState.value = PairingState.AwaitingUserConfirmation(
                sessionTempId = tempId,
                peerDeviceId = peerDeviceId,
                peerName = peerName,
                peerManufacturer = peerMfg,
                peerModel = peerModel,
                peerDeviceType = peerType,
                peerPublicKeyBase64 = peerPub,
                peerFingerprintShort = peerFingerprint,
                sasCode = sasCode,
                host = peerHost,
                port = peerPort,
                isIncoming = !isInitiator
            )

            val userAccepted = withTimeoutOrNull(45_000L) { deferred.await() } ?: false
            pendingConfirmations.remove(tempId)

            writePlainFrame(output, JSONObject().put("accepted", userAccepted).toString())
            val peerAck = JSONObject(readPlainFrame(input))
            val peerAccepted = peerAck.optBoolean("accepted", false)
            if (!userAccepted || !peerAccepted) {
                _pairingState.value = PairingState.Failed("Pairing declined or timed out.")
                socket.close()
                return
            }

            trustedDeviceDao.upsert(
                TrustedDeviceEntity(
                    deviceId = peerDeviceId,
                    displayName = peerName,
                    manufacturer = peerMfg,
                    model = peerModel,
                    deviceType = peerType,
                    publicKeyBase64 = peerPub,
                    fingerprintShort = peerFingerprint,
                    lastHost = peerHost,
                    lastPort = peerPort,
                    isTrusted = true,
                    relaySecretBase64 = relaySecret
                )
            )
            activityLogDao.insert(
                ActivityLogEntity(
                    logId = UUID.randomUUID().toString(),
                    category = "PAIRING",
                    deviceId = peerDeviceId,
                    summary = "Paired with $peerName (SAS $sasCode)"
                )
            )
            _pairingState.value = PairingState.Verified(peerDeviceId, peerName)
        } else {
            // Mutual confirmation for already-trusted devices
            writePlainFrame(output, JSONObject().put("accepted", true).toString())
            val peerAck = JSONObject(readPlainFrame(input))
            if (!peerAck.optBoolean("accepted", false)) {
                socket.close()
                return
            }
            trustedDeviceDao.upsert(
                existingTrusted.copy(
                    displayName = peerName,
                    lastHost = peerHost,
                    lastPort = peerPort,
                    lastConnectedAt = System.currentTimeMillis(),
                    relaySecretBase64 = existingTrusted.relaySecretBase64.ifBlank { relaySecret }
                )
            )
        }

        // Prevent duplicate device sessions
        activeSessions.remove(peerDeviceId)?.close()
        socket.soTimeout = 0

        val transport = EncryptedPeerTransport(peerDeviceId, socket, input, output, cipher)
        activeSessions[peerDeviceId] = transport

        connectedMap[peerDeviceId] = ConnectedPeerState(
            deviceId = peerDeviceId,
            displayName = peerName,
            manufacturer = peerMfg,
            model = peerModel,
            deviceType = peerType,
            host = peerHost,
            port = peerPort,
            status = ConnectionStatus.CONNECTED,
            batteryPercent = peerHello.optInt("battery", -1).takeIf { it in 0..100 },
            isCharging = if (peerHello.has("charging")) peerHello.getBoolean("charging") else null,
            networkType = peerHello.optString("networkType", "WIFI"),
            diagnosticNote = "Encrypted AES-GCM session active"
        )
        publishConnectedPeers()
        runSessionReadLoop(transport, peerName)
    }

    private suspend fun runSessionReadLoop(transport: EncryptedPeerTransport, peerName: String) {
        try {
            while (true) {
                val (frameType, payload) = transport.readEncryptedFrame()
                when (frameType) {
                    WireFrameType.JSON_CONTROL -> {
                        val json = JSONObject(String(payload, Charsets.UTF_8))
                        val msgType = json.optString("type")
                        val body = json.optJSONObject("payload") ?: JSONObject()
                        handleInternalOrDispatch(transport, peerName, msgType, body)
                    }
                    WireFrameType.FILE_CHUNK,
                    WireFrameType.SCREEN_FRAME,
                    WireFrameType.SCREEN_H264_FRAME -> {
                        _binaryEvents.tryEmit(IncomingBinaryEvent(transport.deviceId, frameType, payload))
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Session closed for ${transport.deviceId}: ${e.localizedMessage}")
        } finally {
            activeSessions.remove(transport.deviceId)
            transport.close()
            connectedMap.remove(transport.deviceId)
            publishConnectedPeers()
        }
    }

    private fun handleInternalOrDispatch(
        transport: EncryptedPeerTransport,
        peerName: String,
        msgType: String,
        body: JSONObject
    ) {
        when (msgType) {
            "PING" -> {
                val snap = telemetryProvider.currentSnapshot()
                transport.sendControlJson(
                    "PONG",
                    JSONObject().apply {
                        put("sentAt", body.optLong("sentAt", 0L))
                        put("battery", snap.batteryPercent)
                        put("charging", snap.isCharging)
                        put("networkType", snap.networkType.name)
                    }
                )
            }
            "PONG" -> {
                val sentAt = body.optLong("sentAt", 0L)
                val rtt = if (sentAt > 0L) (System.currentTimeMillis() - sentAt).coerceAtLeast(1L) else null
                val current = connectedMap[transport.deviceId] ?: return
                connectedMap[transport.deviceId] = current.copy(
                    rttMs = rtt,
                    batteryPercent = body.optInt("battery", -1).takeIf { it in 0..100 } ?: current.batteryPercent,
                    isCharging = if (body.has("charging")) body.getBoolean("charging") else current.isCharging,
                    networkType = body.optString("networkType", current.networkType),
                    lastSeenAt = System.currentTimeMillis()
                )
                publishConnectedPeers()
            }
            "DISCONNECT" -> {
                transport.close()
            }
            else -> {
                _controlEvents.tryEmit(IncomingControlEvent(transport.deviceId, peerName, msgType, body))
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(8_000L)
                activeSessions.values.forEach { session ->
                    runCatching {
                        session.sendControlJson("PING", JSONObject().put("sentAt", System.currentTimeMillis()))
                    }
                }
            }
        }
    }

    fun sendControlMessage(deviceId: String, type: String, payload: JSONObject): Boolean {
        val session = activeSessions[deviceId] ?: return false
        return runCatching {
            session.sendControlJson(type, payload)
            true
        }.getOrDefault(false)
    }

    fun sendBinaryPayload(deviceId: String, frameType: WireFrameType, bytes: ByteArray): Boolean {
        val session = activeSessions[deviceId] ?: return false
        return runCatching {
            session.sendBinaryFrame(frameType, bytes)
            true
        }.getOrDefault(false)
    }

    fun broadcastControlMessage(type: String, payload: JSONObject): Int {
        var sentCount = 0
        activeSessions.values.forEach { session ->
            if (runCatching { session.sendControlJson(type, payload); true }.getOrDefault(false)) {
                sentCount++
            }
        }
        return sentCount
    }

    fun disconnectPeer(deviceId: String) {
        activeSessions.remove(deviceId)?.let { session ->
            runCatching { session.sendControlJson("DISCONNECT", JSONObject()) }
            session.close()
        }
        connectedMap.remove(deviceId)
        publishConnectedPeers()
    }

    suspend fun revokeAndForgetDevice(deviceId: String) {
        disconnectPeer(deviceId)
        trustedDeviceDao.deleteById(deviceId)
        activityLogDao.insert(
            ActivityLogEntity(
                logId = UUID.randomUUID().toString(),
                category = "SECURITY",
                deviceId = deviceId,
                summary = "Revoked trust and removed device $deviceId"
            )
        )
    }

    suspend fun disconnectAllDevices() {
        activeSessions.keys.toList().forEach { disconnectPeer(it) }
        activityLogDao.insert(
            ActivityLogEntity(
                logId = UUID.randomUUID().toString(),
                category = "SECURITY",
                summary = "Disconnected all active device sessions"
            )
        )
    }

    private suspend fun reconnectTrustedPeers() {
        val trusted = trustedDeviceDao.getAllTrustedSync()
        trusted.forEach { device ->
            val host = device.lastHost
            if (!host.isNullOrBlank() && device.lastPort > 0 && !activeSessions.containsKey(device.deviceId)) {
                scope.launch {
                    runCatching {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(host, device.lastPort), 2_500)
                        performHandshake(socket, true, device.deviceId, device.publicKeyBase64)
                    }
                }
            }
        }
    }

    fun stopAll() {
        heartbeatJob?.cancel()
        acceptJob?.cancel()
        nsdEngine.stopDiscovery()
        nsdEngine.stopServiceRegistration()
        bleEngine.stopAll()
        activeSessions.values.forEach { it.close() }
        activeSessions.clear()
        connectedMap.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        publishConnectedPeers()
    }

    private fun publishConnectedPeers() {
        _connectedPeers.value = connectedMap.values.sortedByDescending { it.lastSeenAt }
    }

    private fun writePlainFrame(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readPlainFrame(input: DataInputStream): String {
        val len = input.readInt()
        require(len in 2..131_072) { "Invalid handshake length: $len" }
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun getLocalIpv4Address(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    companion object {
        private const val TAG = "ConnectivityEngine"
    }
}
