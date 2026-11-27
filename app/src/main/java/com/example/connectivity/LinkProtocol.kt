package com.example.connectivity

import com.example.security.EncryptedSessionCipher
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

enum class DiscoveryTransport {
    WIFI_NSD,
    WIFI_DIRECT,
    BLE,
    CLOUD_PRESENCE
}

enum class TransportRoute(val label: String) {
    LOCAL_WIFI("Local Wi-Fi"),
    WIFI_DIRECT("Wi-Fi Direct"),
    CLOUD_RELAY("E2EE Cloud Relay")
}

enum class ConnectionStatus {
    DISCOVERED,
    CONNECTING,
    AWAITING_CONFIRMATION,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

data class DiscoveredPeer(
    val deviceId: String,
    val displayName: String,
    val manufacturer: String,
    val model: String,
    val deviceType: String,
    val host: String,
    val port: Int,
    val transport: DiscoveryTransport,
    val signalRssi: Int? = null,
    val publicKeyBase64: String? = null,
    val fingerprintShort: String? = null,
    val discoveredAt: Long = System.currentTimeMillis()
)

data class ConnectedPeerState(
    val deviceId: String,
    val displayName: String,
    val manufacturer: String,
    val model: String,
    val deviceType: String,
    val host: String,
    val port: Int,
    val status: ConnectionStatus,
    val rttMs: Long? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val networkType: String = "WIFI",
    val lastSeenAt: Long = System.currentTimeMillis(),
    val diagnosticNote: String = ""
)

sealed interface PairingState {
    data object Idle : PairingState
    data class Handshaking(val peerName: String) : PairingState
    data class AwaitingUserConfirmation(
        val sessionTempId: String,
        val peerDeviceId: String,
        val peerName: String,
        val peerManufacturer: String,
        val peerModel: String,
        val peerDeviceType: String,
        val peerPublicKeyBase64: String,
        val peerFingerprintShort: String,
        val sasCode: String,
        val host: String,
        val port: Int,
        val isIncoming: Boolean
    ) : PairingState
    data class Verified(val deviceId: String, val peerName: String) : PairingState
    data class Failed(val reason: String) : PairingState
}

enum class WireFrameType(val code: Byte) {
    JSON_CONTROL(1),
    FILE_CHUNK(2),
    SCREEN_FRAME(3),
    SCREEN_H264_FRAME(4);

    companion object {
        fun fromCode(code: Byte): WireFrameType? = entries.firstOrNull { it.code == code }
    }
}

class EncryptedPeerTransport(
    val deviceId: String,
    val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val cipher: EncryptedSessionCipher
) {
    @Synchronized
    fun sendControlJson(type: String, payload: JSONObject) {
        val envelope = JSONObject().apply {
            put("type", type)
            put("payload", payload)
            put("ts", System.currentTimeMillis())
        }
        sendEncrypted(WireFrameType.JSON_CONTROL, envelope.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun sendBinaryFrame(frameType: WireFrameType, bytes: ByteArray) {
        sendEncrypted(frameType, bytes)
    }

    private fun sendEncrypted(frameType: WireFrameType, plain: ByteArray) {
        val raw = ByteArray(1 + plain.size)
        raw[0] = frameType.code
        System.arraycopy(plain, 0, raw, 1, plain.size)
        val encrypted = cipher.encryptFrame(raw)
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
    }

    fun readEncryptedFrame(): Pair<WireFrameType, ByteArray> {
        val length = input.readInt()
        require(length in 21..8_388_608) { "Invalid encrypted frame length: $length" }
        val encrypted = ByteArray(length)
        input.readFully(encrypted)
        val decrypted = cipher.decryptFrame(encrypted)
        val frameType = WireFrameType.fromCode(decrypted[0])
            ?: throw IllegalStateException("Unknown frame type: ${decrypted[0]}")
        val payload = decrypted.copyOfRange(1, decrypted.size)
        return frameType to payload
    }

    fun close() {
        runCatching { socket.close() }
    }
}
