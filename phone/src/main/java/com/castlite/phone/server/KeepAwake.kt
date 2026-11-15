package com.castlite.phone.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Decides whether the phone must stay awake. Pure logic, unit-tested.
 *
 * "Activity" is anything that proves a TV still depends on us: a casting session that
 * is playing/loading, or an HTTP request to the media server. We stay awake for
 * [idleGraceMs] after the last activity, so a paused movie can be resumed from the
 * lock screen, but a forgotten session doesn't drain the battery overnight.
 */
class KeepAwakePolicy(private val idleGraceMs: Long = 30 * 60_000L) {
    @Volatile var lastActivityMs: Long = 0
        private set

    fun onActivity(nowMs: Long) {
        lastActivityMs = nowMs
    }

    fun shouldStayAwake(nowMs: Long): Boolean = lastActivityMs > 0 && nowMs - lastActivityMs < idleGraceMs
}

/**
 * Holds a partial wake lock (CPU keeps running with the screen off, so the server keeps
 * sending bytes and the DLNA queue/slideshow timers keep firing) and a Wi-Fi lock while
 * [KeepAwakePolicy] says a TV depends on us. Both are released when idle.
 *
 * The wake lock is taken with a timeout and renewed while busy, so even if something
 * goes wrong it can never be held forever.
 */
class KeepAwake(context: Context, private val policy: KeepAwakePolicy = KeepAwakePolicy()) {

    private val app = context.applicationContext
    private val wakeLock: PowerManager.WakeLock? =
        app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CastLite:casting")
            ?.apply { setReferenceCounted(false) }
    private val wifiLock: WifiManager.WifiLock? = createWifiLock()
    private var lastRenewMs = 0L

    val isHeld: Boolean get() = wakeLock?.isHeld == true

    /** Something happened that a TV depends on: make sure we're awake. */
    fun onActivity() {
        val now = System.currentTimeMillis()
        policy.onActivity(now)
        // Renewing is cheap, but there's no need to do it on every status update.
        if (!isHeld || now - lastRenewMs > RENEW_EVERY_MS) acquire(now)
    }

    /** Called periodically: renew while busy, release once idle. */
    fun check() {
        val now = System.currentTimeMillis()
        if (policy.shouldStayAwake(now)) acquire(now) else release()
    }

    private fun acquire(now: Long) {
        // Re-acquiring a non-reference-counted lock just extends its timeout.
        wakeLock?.acquire(LOCK_TIMEOUT_MS)
        if (wifiLock?.isHeld == false) wifiLock.acquire()
        if (lastRenewMs == 0L || !isHeld) Log.d(TAG, "Staying awake for casting")
        lastRenewMs = now
    }

    fun release() {
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
            Log.d(TAG, "Idle: released wake lock")
        }
        if (wifiLock?.isHeld == true) wifiLock.release()
        lastRenewMs = 0
    }

    @Suppress("DEPRECATION")
    private fun createWifiLock(): WifiManager.WifiLock? {
        val wifi = app.getSystemService(WifiManager::class.java) ?: return null
        // Before Android 14, HIGH_PERF keeps Wi-Fi out of power-save even with the screen off.
        // From Android 14 that mode does nothing; LOW_LATENCY is the remaining option (it only
        // applies while the screen is on). With the screen off, Android 14+ may use Wi-Fi
        // power-save, but the radio stays connected and the TV's buffer covers the gaps.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        return wifi.createWifiLock(mode, "CastLite:casting").apply { setReferenceCounted(false) }
    }

    companion object {
        private const val TAG = "KeepAwake"
        /** Each acquire lasts this long unless renewed. */
        const val LOCK_TIMEOUT_MS = 15 * 60_000L
        /** How often [check] should run. */
        const val CHECK_EVERY_MS = 5 * 60_000L
        private const val RENEW_EVERY_MS = 60_000L
    }
}
