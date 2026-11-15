package com.castlite.updater

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Runs in the *new* version right after an update. Android doesn't restart an app that
 * replaced itself, so we try to reopen it (allowed on many TVs) and also post a
 * "CastLite was updated — tap to open" notification (phones).
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pm = context.packageManager
        val launch = (pm.getLeanbackLaunchIntentForPackage(context.packageName) ?: pm.getLaunchIntentForPackage(context.packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.d(TAG, "Not allowed to reopen after update: ${e.message}")
        }
        notifyUpdated(context, launch)
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked at the top (Android 13+).
    private fun notifyUpdated(context: Context, launch: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val label = context.applicationInfo.loadLabel(context.packageManager)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$label was updated")
            .setContentText("Version $version is ready. Tap to open.")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val TAG = "UpdatedReceiver"
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 4242
    }
}
