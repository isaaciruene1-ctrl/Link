package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class DeviceFormFactor {
    PHONE,
    TABLET,
    OTHER
}

enum class NetworkTransportType {
    WIFI,
    CELLULAR,
    OFFLINE,
    UNKNOWN
}

data class LocalTelemetry(
    val manufacturer: String,
    val model: String,
    val formFactor: DeviceFormFactor,
    val androidVersionLabel: String,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: NetworkTransportType
)

class SystemTelemetryProvider(private val context: Context) {

    fun currentSnapshot(): LocalTelemetry {
        val battery = readBatteryState()
        val network = readNetworkType()
        val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp
        val formFactor = if (smallestWidthDp >= 600) DeviceFormFactor.TABLET else DeviceFormFactor.PHONE
        return LocalTelemetry(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            formFactor = formFactor,
            androidVersionLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            batteryPercent = battery.first,
            isCharging = battery.second,
            networkType = network
        )
    }

    fun observeTelemetry(): Flow<LocalTelemetry> = callbackFlow {
        trySend(currentSnapshot())

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(currentSnapshot())
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentSnapshot())
            }

            override fun onLost(network: Network) {
                trySend(currentSnapshot())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(currentSnapshot())
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(networkCallback) }

        awaitClose {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            runCatching { cm?.unregisterNetworkCallback(networkCallback) }
        }
    }.distinctUntilChanged()

    private fun readBatteryState(): Pair<Int, Boolean> {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) capacity else -1
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return percent to isCharging
    }

    private fun readNetworkType(): NetworkTransportType {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkTransportType.UNKNOWN
        val activeNetwork = cm.activeNetwork ?: return NetworkTransportType.OFFLINE
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkTransportType.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
            else -> NetworkTransportType.UNKNOWN
        }
    }
}
