package com.example.connectivity

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class NsdDiscoveryEngine(private val context: Context) {

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }
    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var localServiceName: String? = null

    private val peersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _statusText = MutableStateFlow("Idle")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    fun startServiceRegistration(
        deviceId: String,
        displayName: String,
        manufacturer: String,
        model: String,
        deviceType: String,
        port: Int
    ) {
        stopServiceRegistration()
        if (port <= 0) return
        val manager = nsdManager ?: run {
            _statusText.value = "NSD unavailable on this device"
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "LINK-${displayName.take(18)}-${deviceId.takeLast(6)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId.take(40))
            setAttribute("name", displayName.take(30))
            setAttribute("mfg", manufacturer.take(24))
            setAttribute("mdl", model.take(24))
            setAttribute("type", deviceType)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                localServiceName = info.serviceName
                Log.d(TAG, "NSD registered: ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                localServiceName = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.e(TAG, "Failed to register NSD service", it)
        }
    }

    fun stopServiceRegistration() {
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching { nsdManager?.unregisterService(listener) }
    }

    fun startDiscovery(localDeviceId: String) {
        stopDiscovery()
        val manager = nsdManager ?: run {
            _statusText.value = "NSD service unavailable"
            return
        }

        runCatching {
            multicastLock = wifiManager?.createMulticastLock("LINK_NSD_LOCK")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _statusText.value = "Scanning LAN via NSD"
                Log.d(TAG, "NSD discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                if (service.serviceName == localServiceName) return
                resolvePeer(service, localDeviceId)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val removedKeys = peersMap.entries
                    .filter { it.value.displayName == service.serviceName || it.key == service.serviceName }
                    .map { it.key }
                removedKeys.forEach { peersMap.remove(it) }
                _discoveredPeers.value = peersMap.values.sortedByDescending { it.discoveredAt }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _statusText.value = "Discovery stopped"
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _statusText.value = "NSD discovery error ($errorCode)"
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD stop discovery failed: $errorCode")
            }
        }

        discoveryListener = listener
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            _statusText.value = "Failed to start NSD scan: ${it.localizedMessage}"
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePeer(service: NsdServiceInfo, localDeviceId: String) {
        val manager = nsdManager ?: return
        runCatching {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val attrs = serviceInfo.attributes
                    val peerId = attrs["id"]?.toString(Charsets.UTF_8) ?: serviceInfo.serviceName
                    if (peerId == localDeviceId) return
                    val host = serviceInfo.host?.hostAddress ?: return
                    val peer = DiscoveredPeer(
                        deviceId = peerId,
                        displayName = attrs["name"]?.toString(Charsets.UTF_8) ?: serviceInfo.serviceName,
                        manufacturer = attrs["mfg"]?.toString(Charsets.UTF_8) ?: "Android",
                        model = attrs["mdl"]?.toString(Charsets.UTF_8) ?: "Device",
                        deviceType = attrs["type"]?.toString(Charsets.UTF_8) ?: "PHONE",
                        host = host,
                        port = serviceInfo.port,
                        transport = DiscoveryTransport.WIFI_NSD
                    )
                    peersMap[peer.deviceId] = peer
                    _discoveredPeers.value = peersMap.values.sortedByDescending { it.discoveredAt }
                }
            })
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) {
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdDiscoveryEngine"
        const val SERVICE_TYPE = "_linkeco._tcp."
    }
}
