package com.castlite.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Gets PackageInstaller's verdict. If Android needs the user to confirm, it hands us
 * its "Update this app?" screen to show; otherwise it reports success or failure.
 * (On success the app is replaced and restarted by the system — just reopen it.)
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val version = intent.getLongExtra(EXTRA_VERSION, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't show the install dialog", e)
                    AppUpdater.instance?.onInstallFinished(PackageInstaller.STATUS_FAILURE, "Couldn't show the install dialog", version)
                }
            }
            return
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "Install finished: status=$status $message")
        AppUpdater.instance?.onInstallFinished(status, message, version)
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val ACTION = "com.castlite.updater.INSTALL_RESULT"
        const val EXTRA_VERSION = "com.castlite.updater.VERSION"
    }
}
