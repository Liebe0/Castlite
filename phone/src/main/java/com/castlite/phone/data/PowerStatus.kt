package com.castlite.phone.data

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android lets CastLite keep working while the phone is locked.
 *
 * The foreground service already survives the screen turning off, but battery
 * optimization (and phone makers' own "battery savers") can still freeze or kill it
 * after a while. Being exempt is the most reliable setting for long movies.
 */
data class PowerStatus(
    /** True if CastLite is exempt from battery optimization. */
    val unrestrictedBattery: Boolean = true,
    /** True if the user turned off background activity for CastLite (Android 9+). */
    val backgroundRestricted: Boolean = false,
)

object PowerChecks {

    fun read(context: Context): PowerStatus {
        val power = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        return PowerStatus(
            unrestrictedBattery = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && am?.isBackgroundRestricted == true,
        )
    }

    /** System dialog: "Let CastLite always run in the background?" */
    @SuppressLint("BatteryLife") // Exactly what a casting server needs; this app isn't distributed on Play.
    fun exemptionRequest(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Fallback: the list of apps' battery optimization settings. */
    fun batterySettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** CastLite's own settings page (to lift a background restriction). */
    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
