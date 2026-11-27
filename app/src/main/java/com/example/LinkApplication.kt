package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.di.LinkAppContainer
import com.google.firebase.FirebaseApp

class LinkApplication : Application() {

    val container: LinkAppContainer by lazy { LinkAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_CONNECTIVITY,
                    "LINK Connectivity",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Maintains local device discovery and encrypted LAN sessions."
                },
                NotificationChannel(
                    CHANNEL_TRANSFERS,
                    "File Transfers",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress for incoming and outgoing encrypted file transfers."
                },
                NotificationChannel(
                    CHANNEL_SCREENS,
                    "Screen Sharing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Indicates when active screen capture or mirroring is running."
                },
                NotificationChannel(
                    CHANNEL_HANDOFF,
                    "Smart Handoff & Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Alerts for incoming handoff cards and pairing requests."
                }
            )
            manager.createNotificationChannels(channels)
        }
    }

    companion object {
        const val CHANNEL_CONNECTIVITY = "link_connectivity"
        const val CHANNEL_TRANSFERS = "link_transfers"
        const val CHANNEL_SCREENS = "link_screens"
        const val CHANNEL_HANDOFF = "link_handoff"
    }
}
