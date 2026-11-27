package com.example.connectivity

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.LinkApplication
import com.example.MainActivity
import com.example.R

class LinkConnectivityService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val app = application as? LinkApplication
        app?.container?.connectivityEngine?.startTransportAndDiscovery()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LinkApplication.CHANNEL_CONNECTIVITY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LINK Ecosystem Active")
            .setContentText("Encrypted local discovery and continuity listener running")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.example.connectivity.ACTION_START"
        const val ACTION_STOP = "com.example.connectivity.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, LinkConnectivityService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LinkConnectivityService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
