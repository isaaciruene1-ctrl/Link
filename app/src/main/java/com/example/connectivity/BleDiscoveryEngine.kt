package com.example.connectivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.core.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleDiscoveryEngine(private val context: Context) {

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val blePeersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val _blePeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val blePeers: StateFlow<List<DiscoveredPeer>> = _blePeers.asStateFlow()

    private val _bleStatus = MutableStateFlow("BLE idle")
    val bleStatus: StateFlow<String> = _bleStatus.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    fun isBluetoothReady(): Boolean {
        val adapter = bluetoothManager?.adapter ?: return false
        return adapter.isEnabled &&
            PermissionHelper.hasPermissions(context, PermissionHelper.requiredBluetoothPermissions())
    }

    @SuppressLint("MissingPermission")
    fun startBleScanAndAdvertise(localDeviceId: String, localName: String) {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            _bleStatus.value = "Bluetooth hardware unavailable"
            return
        }
        if (!adapter.isEnabled) {
            _bleStatus.value = "Bluetooth is turned off"
            return
        }
        if (!PermissionHelper.hasPermissions(context, PermissionHelper.requiredBluetoothPermissions())) {
            _bleStatus.value = "Bluetooth permissions required"
            return
        }

        startScanning(localDeviceId)
        startAdvertising(localDeviceId, localName)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(localDeviceId: String) {
        stopScanning()
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: run {
            _bleStatus.value = "BLE scanner unavailable"
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(LINK_SERVICE_UUID)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val payloadBytes = record.getServiceData(LINK_SERVICE_UUID)
                val shortId = payloadBytes?.toString(Charsets.UTF_8)
                    ?: result.device.address
                if (localDeviceId.endsWith(shortId)) return
                val peerName = record.deviceName ?: result.device.name ?: "Nearby LINK Device"
                val peer = DiscoveredPeer(
                    deviceId = "ble_$shortId",
                    displayName = peerName,
                    manufacturer = "Bluetooth LE",
                    model = result.device.address,
                    deviceType = "PHONE",
                    host = result.device.address,
                    port = 0,
                    transport = DiscoveryTransport.BLE,
                    signalRssi = result.rssi
                )
                blePeersMap[peer.deviceId] = peer
                _blePeers.value = blePeersMap.values.sortedByDescending { it.discoveredAt }
            }

            override fun onScanFailed(errorCode: Int) {
                _bleStatus.value = "BLE scan failed ($errorCode)"
            }
        }

        scanCallback = callback
        runCatching {
            scanner.startScan(listOf(filter), settings, callback)
            _bleStatus.value = "BLE scanning active"
        }.onFailure {
            Log.w(TAG, "BLE scan error", it)
            _bleStatus.value = "BLE scan unavailable"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(localDeviceId: String, localName: String) {
        stopAdvertising()
        val advertiser = bluetoothManager?.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val shortTag = localDeviceId.takeLast(8).toByteArray(Charsets.UTF_8)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(LINK_SERVICE_UUID)
            .addServiceData(LINK_SERVICE_UUID, shortTag)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "BLE advertising active for $localName")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE advertise failed: $errorCode")
            }
        }
        advertiseCallback = callback
        runCatching { advertiser.startAdvertising(settings, data, callback) }
    }

    @SuppressLint("MissingPermission")
    fun stopAll() {
        stopScanning()
        stopAdvertising()
        _bleStatus.value = "BLE idle"
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        val cb = scanCallback ?: return
        scanCallback = null
        if (PermissionHelper.hasPermissions(context, PermissionHelper.requiredBluetoothPermissions())) {
            runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        advertiseCallback = null
        if (PermissionHelper.hasPermissions(context, PermissionHelper.requiredBluetoothPermissions())) {
            runCatching { bluetoothManager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
        }
    }

    companion object {
        private const val TAG = "BleDiscoveryEngine"
        val LINK_SERVICE_UUID = ParcelUuid(UUID.fromString("7a4b9001-4c8d-4f3e-9a21-6b8e0d5c1100"))
    }
}
