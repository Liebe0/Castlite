package com.castlite.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CastLiteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_CASTING,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW, // silent, no heads-up
        ).apply { description = getString(R.string.notification_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_CASTING = "casting"
    }
}
