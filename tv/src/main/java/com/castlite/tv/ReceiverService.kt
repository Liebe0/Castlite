package com.castlite.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the receiver listening while the TV shows other apps (and from boot), so the
 * phone can always find the TV and start casting without anyone opening CastLite.
 */
class ReceiverService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        ReceiverRuntime.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ReceiverRuntime.start() // idempotent; also refreshes the address
        return START_STICKY // if Android kills it, bring the receiver back
    }

    override fun onDestroy() {
        ReceiverRuntime.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "CastLite receiver", NotificationManager.IMPORTANCE_MIN),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("CastLite is ready")
            .setContentText("Waiting for your phone")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
            } catch (e: Exception) {
                TvLog.log("app", "Couldn't start the receiver service: ${e.message}")
            }
        }
    }
}
