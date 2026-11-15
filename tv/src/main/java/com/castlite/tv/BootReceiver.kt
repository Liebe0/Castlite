package com.castlite.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts listening when the TV boots, and again right after CastLite updates itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" -> {
                TvLog.log("app", "Starting receiver after ${intent.action}")
                ReceiverService.start(context)
            }
        }
    }
}
