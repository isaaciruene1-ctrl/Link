package com.example.connectivity

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.example.core.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WifiP2pPeerInfo(
    val deviceName: String,
    val deviceAddress: String,
    val statusLabel: String,
    val isGroupOwner: Boolean
)

data class WifiP2pGroupSnapshot(
    val p2pEnabled: Boolean = false,
    val isGroupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val networkName: String? = null,
    val statusText: String = "Wi-Fi Direct idle"
)

class WifiP2pEngine(
    private val context: Context
) {
    private val manager: WifiP2pManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var onGroupOwnerIpReady: ((String, Boolean) -> Unit)? = null

    private val _peers = MutableStateFlow<List<WifiP2pPeerInfo>>(emptyList())
    val peers: StateFlow<List<WifiP2pPeerInfo>> = _peers.asStateFlow()

    private val _groupState = MutableStateFlow(WifiP2pGroupSnapshot())
    val groupState: StateFlow<WifiP2pGroupSnapshot> = _groupState.asStateFlow()

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _groupState.value = _groupState.value.copy(
                        p2pEnabled = enabled,
                        statusText = if (enabled) "Wi-Fi Direct ready" else "Wi-Fi Direct disabled in system settings"
                    )
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeersInternal()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ch = channel ?: return
                    manager?.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                        if (info == null) return@requestConnectionInfo
                        val host = info.groupOwnerAddress?.hostAddress
                        _groupState.value = _groupState.value.copy(
                            isGroupFormed = info.groupFormed,
                            isGroupOwner = info.isGroupOwner,
                            groupOwnerAddress = host,
                            statusText = if (info.groupFormed) {
                                if (info.isGroupOwner) "Wi-Fi Direct Group Owner active (${host ?: "local"})"
                                else "Connected to Wi-Fi Direct Group Owner ($host)"
                            } else {
                                "Wi-Fi Direct ready"
                            }
                        )
                        if (info.groupFormed && !host.isNullOrBlank()) {
                            onGroupOwnerIpReady?.invoke(host, info.isGroupOwner)
                        }
                    }
                    if (PermissionHelper.hasPermissions(context, PermissionHelper.requiredNearbyWifiPermissions())) {
                        runCatching {
                            manager?.requestGroupInfo(ch) { group ->
                                if (group != null) {
                                    _groupState.value = _groupState.value.copy(networkName = group.networkName)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun initialize(onGroupReady: (groupOwnerHost: String, isGroupOwner: Boolean) -> Unit) {
        onGroupOwnerIpReady = onGroupReady
        val mgr = manager ?: run {
            _groupState.value = _groupState.value.copy(statusText = "Wi-Fi Direct hardware unavailable")
            return
        }
        if (channel == null) {
            channel = mgr.initialize(context.applicationContext, Looper.getMainLooper(), null)
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.applicationContext.registerReceiver(p2pReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        val mgr = manager ?: return
        val ch = channel ?: run {
            initialize(onGroupOwnerIpReady ?: { _, _ -> })
            channel
        } ?: return

        if (!PermissionHelper.hasPermissions(context, PermissionHelper.requiredNearbyWifiPermissions())) {
            _groupState.value = _groupState.value.copy(
                statusText = "Nearby Wi-Fi permission required for Wi-Fi Direct"
            )
            return
        }

        _groupState.value = _groupState.value.copy(statusText = "Scanning Wi-Fi Direct peers...")
        runCatching {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _groupState.value = _groupState.value.copy(statusText = "Wi-Fi Direct peer discovery active")
                    requestPeersInternal()
                }

                override fun onFailure(reason: Int) {
                    _groupState.value = _groupState.value.copy(
                        statusText = "Wi-Fi Direct discovery error ($reason)"
                    )
                }
            })
        }.onFailure {
            Log.w(TAG, "discoverPeers failed", it)
            _groupState.value = _groupState.value.copy(statusText = "Wi-Fi Direct permission or radio unavailable")
        }
    }

    @SuppressLint("MissingPermission")
    fun createDirectGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (!PermissionHelper.hasPermissions(context, PermissionHelper.requiredNearbyWifiPermissions())) {
            _groupState.value = _groupState.value.copy(statusText = "Nearby Wi-Fi permission required")
            return
        }
        runCatching {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _groupState.value = _groupState.value.copy(statusText = "Creating Wi-Fi Direct group...")
                }

                override fun onFailure(reason: Int) {
                    _groupState.value = _groupState.value.copy(statusText = "Create group failed ($reason)")
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String) {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (!PermissionHelper.hasPermissions(context, PermissionHelper.requiredNearbyWifiPermissions())) {
            _groupState.value = _groupState.value.copy(statusText = "Nearby Wi-Fi permission required")
            return
        }
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }
        runCatching {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _groupState.value = _groupState.value.copy(statusText = "Negotiating Wi-Fi Direct link...")
                }

                override fun onFailure(reason: Int) {
                    _groupState.value = _groupState.value.copy(statusText = "Wi-Fi Direct connect failed ($reason)")
                }
            })
        }
    }

    fun removeGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _groupState.value = _groupState.value.copy(
                        isGroupFormed = false,
                        isGroupOwner = false,
                        groupOwnerAddress = null,
                        networkName = null,
                        statusText = "Wi-Fi Direct group closed"
                    )
                }

                override fun onFailure(reason: Int) {
                    _groupState.value = _groupState.value.copy(statusText = "Leave group status ($reason)")
                }
            })
        }
    }

    fun stop() {
        if (receiverRegistered) {
            runCatching { context.applicationContext.unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeersInternal() {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (!PermissionHelper.hasPermissions(context, PermissionHelper.requiredNearbyWifiPermissions())) return
        runCatching {
            mgr.requestPeers(ch) { peerList ->
                _peers.value = peerList.deviceList.map { device ->
                    WifiP2pPeerInfo(
                        deviceName = device.deviceName.ifBlank { device.deviceAddress },
                        deviceAddress = device.deviceAddress,
                        statusLabel = statusToText(device.status),
                        isGroupOwner = device.isGroupOwner
                    )
                }
            }
        }
    }

    private fun statusToText(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }

    companion object {
        private const val TAG = "WifiP2pEngine"
    }
}
