package com.example.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.connectivity.ConnectivityManagerEngine
import com.example.connectivity.RemoteRelayEngine
import com.example.connectivity.TransportRoute
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.TrustedDeviceDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class MultiControlUiState(
    val receiverArmed: Boolean = false,
    val activeRemoteControllerName: String? = null,
    val lastRoute: TransportRoute? = null,
    val statusText: String = "Multi-Control receiver disarmed"
)

class InputControlEngine(
    private val context: Context,
    private val connectivityEngine: ConnectivityManagerEngine,
    private val remoteRelayEngine: RemoteRelayEngine,
    private val trustedDeviceDao: TrustedDeviceDao,
    private val activityLogDao: ActivityLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MultiControlUiState())
    val state: StateFlow<MultiControlUiState> = _state.asStateFlow()

    init {
        scope.launch {
            connectivityEngine.controlEvents.collect { event ->
                if (event.type == "REMOTE_INPUT_EVENT") {
                    handleIncomingInput(event.peerDeviceId, event.peerName, event.payload)
                }
            }
        }
    }

    fun setReceiverArmed(armed: Boolean) {
        _state.value = _state.value.copy(
            receiverArmed = armed,
            activeRemoteControllerName = if (!armed) null else _state.value.activeRemoteControllerName,
            statusText = if (armed) {
                "Remote input armed — waiting for trusted peer"
            } else {
                "Multi-Control receiver disarmed"
            }
        )
    }

    fun sendTap(targetDeviceId: String, nx: Float, ny: Float) {
        dispatchInput(
            targetDeviceId,
            JSONObject().apply {
                put("kind", "TAP")
                put("nx", nx.toDouble())
                put("ny", ny.toDouble())
            }
        )
    }

    fun sendSwipe(targetDeviceId: String, sx: Float, sy: Float, ex: Float, ey: Float) {
        dispatchInput(
            targetDeviceId,
            JSONObject().apply {
                put("kind", "SWIPE")
                put("sx", sx.toDouble())
                put("sy", sy.toDouble())
                put("ex", ex.toDouble())
                put("ey", ey.toDouble())
            }
        )
    }

    fun sendNavigationAction(targetDeviceId: String, action: String) {
        dispatchInput(
            targetDeviceId,
            JSONObject().apply {
                put("kind", "NAV")
                put("action", action)
            }
        )
    }

    fun sendVirtualText(targetDeviceId: String, text: String) {
        if (text.isBlank()) return
        dispatchInput(
            targetDeviceId,
            JSONObject().apply {
                put("kind", "TYPE_TEXT")
                put("text", text.take(500))
            }
        )
    }

    private fun dispatchInput(targetDeviceId: String, payload: JSONObject) {
        scope.launch {
            val route = remoteRelayEngine.sendSmartControlMessage(
                targetDeviceId = targetDeviceId,
                type = "REMOTE_INPUT_EVENT",
                payload = payload
            )
            _state.value = _state.value.copy(
                lastRoute = route,
                statusText = if (route != null) {
                    "Sent ${payload.optString("kind")} via ${route.label}"
                } else {
                    "Target device unreachable (enable local Wi-Fi or Cloud Relay)"
                }
            )
        }
    }

    private suspend fun handleIncomingInput(peerDeviceId: String, peerName: String, payload: JSONObject) {
        if (!_state.value.receiverArmed) return
        val trusted = trustedDeviceDao.getById(peerDeviceId) ?: return
        if (!trusted.isTrusted || !trusted.allowRemoteControl) return

        val service = LinkInputAccessibilityService.currentInstance()
        val kind = payload.optString("kind")
        var handled = false

        when (kind) {
            "TAP" -> {
                val nx = payload.optDouble("nx", 0.5).toFloat()
                val ny = payload.optDouble("ny", 0.5).toFloat()
                handled = service?.performNormalizedTap(nx, ny) == true
            }
            "SWIPE" -> {
                val sx = payload.optDouble("sx", 0.5).toFloat()
                val sy = payload.optDouble("sy", 0.7).toFloat()
                val ex = payload.optDouble("ex", 0.5).toFloat()
                val ey = payload.optDouble("ey", 0.3).toFloat()
                handled = service?.performNormalizedSwipe(sx, sy, ex, ey) == true
            }
            "NAV" -> {
                val action = payload.optString("action")
                handled = service?.performGlobalNavigation(action) == true
            }
            "TYPE_TEXT" -> {
                val text = payload.optString("text").take(500)
                if (text.isNotEmpty()) {
                    val typed = service?.insertTextIntoFocusedField(text) == true
                    if (!typed) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("LINK Remote Text", text))
                    }
                    handled = true
                }
            }
        }

        _state.value = _state.value.copy(
            activeRemoteControllerName = peerName,
            statusText = if (handled) {
                "Active remote input from $peerName ($kind)"
            } else {
                "Received $kind from $peerName (enable Accessibility service to inject gestures)"
            }
        )

        activityLogDao.insert(
            ActivityLogEntity(
                logId = UUID.randomUUID().toString(),
                category = "INPUT",
                deviceId = peerDeviceId,
                summary = "$peerName sent remote input: $kind"
            )
        )
    }
}
