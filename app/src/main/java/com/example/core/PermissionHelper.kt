package com.example.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.continuity.LinkNotificationListenerService
import com.example.input.LinkInputAccessibilityService

data class PermissionStatusSnapshot(
    val nearbyWifiGranted: Boolean,
    val bluetoothGranted: Boolean,
    val notificationsGranted: Boolean,
    val notificationListenerGranted: Boolean,
    val accessibilityInputGranted: Boolean,
    val cameraGranted: Boolean
)

object PermissionHelper {

    fun requiredNearbyWifiPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun requiredNotificationPermission(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    fun hasPermissions(context: Context, permissions: Array<String>): Boolean =
        permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val componentName = ComponentName(context, LinkNotificationListenerService::class.java)
        return enabledListeners.split(':').any { entry ->
            entry.equals(componentName.flattenToString(), ignoreCase = true) ||
                entry.equals(componentName.flattenToShortString(), ignoreCase = true)
        }
    }

    fun isAccessibilityInputEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val componentName = ComponentName(context, LinkInputAccessibilityService::class.java)
        return enabledServices.split(':').any { entry ->
            entry.equals(componentName.flattenToString(), ignoreCase = true) ||
                entry.equals(componentName.flattenToShortString(), ignoreCase = true)
        }
    }

    fun snapshot(context: Context): PermissionStatusSnapshot =
        PermissionStatusSnapshot(
            nearbyWifiGranted = hasPermissions(context, requiredNearbyWifiPermissions()),
            bluetoothGranted = hasPermissions(context, requiredBluetoothPermissions()),
            notificationsGranted = hasPermissions(context, requiredNotificationPermission()),
            notificationListenerGranted = isNotificationListenerEnabled(context),
            accessibilityInputGranted = isAccessibilityInputEnabled(context),
            cameraGranted = hasPermissions(context, arrayOf(Manifest.permission.CAMERA))
        )

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
