package com.castlite.phone.server

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.castlite.phone.CastLiteApp
import com.castlite.phone.MainActivity
import com.castlite.phone.R
import com.castlite.phone.cast.CastManager
import com.castlite.phone.cast.CastSession
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.media.MediaLibrary
import com.castlite.phone.net.LanMonitor
import com.castlite.phone.net.LanStatus
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Foreground service that owns the [MediaHttpServer]. Running as a foreground service
 * (with a visible notification) keeps Android from killing the server while the
 * screen is off or another app is in front, which would stop playback on the TV.
 *
 * It also holds a Wi-Fi lock so the radio doesn't drop into power save mid-stream.
 */
class MediaServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: MediaHttpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CastManager.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> CastManager.togglePlayPause()
            ACTION_NEXT -> CastManager.next()
        }

        // Must be called within a few seconds of startForegroundService().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(LanStatus.Disconnected, 0, null, null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )

        if (server == null) {
            val started = startServer()
            if (started == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            server = started
            acquireLocks()
            observeForNotification(started.listeningPort)
        }
        // Not sticky: after a crash the picked-file permissions are gone anyway, so the
        // user should reopen the app rather than have a server with nothing to serve.
        return START_NOT_STICKY
    }

    /** Binds to the preferred port, or the next free one. */
    private fun startServer(): MediaHttpServer? {
        var lastError: IOException? = null
        repeat(MediaHttpServer.PORT_ATTEMPTS) { attempt ->
            val port = MediaHttpServer.PREFERRED_PORT + attempt
            val candidate = MediaHttpServer(ContentMediaOpener(contentResolver), port)
            try {
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "Media server listening on port $port")
                ServerMonitor.setState(ServerState.Running(port))
                return candidate
            } catch (e: IOException) {
                lastError = e
            }
        }
        Log.e(TAG, "Could not start server", lastError)
        ServerMonitor.setState(
            ServerState.Failed("Couldn't open a network port (${lastError?.message}). Try again or restart the phone.")
        )
        return null
    }

    /** Keeps the notification text in sync with the address and number of files. */
    private fun observeForNotification(port: Int) {
        val lan = LanMonitor(this)
        scope.launch {
            combine(lan.status, MediaLibrary.items, CastManager.session) { status, items, session ->
                buildNotification(status, items.size, port, session)
            }.collect { notify(it) }
        }
    }

    @SuppressLint("MissingPermission") // Checked by NotificationManagerCompat; no-op if denied.
    private fun notify(notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(lan: LanStatus, fileCount: Int, port: Int?, session: CastSession?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MediaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = session?.current?.displayName?.let { "Casting · $it" } ?: "CastLite media server"
        val text = when {
            port == null -> "Starting…"
            !lan.isConnected -> "Wi-Fi disconnected — the TV can't reach this phone"
            session != null -> "${session.device.name} · ${session.device.protocol.shortLabel} · " +
                session.status.state.name.lowercase().replaceFirstChar { it.uppercase() }
            else -> "Sharing $fileCount file(s) at http://${lan.ipAddress}:$port"
        }
        val builder = NotificationCompat.Builder(this, CastLiteApp.CHANNEL_CASTING)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(0xFF4FC3F7.toInt())
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (session != null) {
            val playing = session.status.state == PlayerState.PLAYING
            builder.addAction(0, if (playing) "Pause" else "Play", serviceIntent(ACTION_TOGGLE, 2))
            if (session.status.index + 1 < session.queue.size) builder.addAction(0, "Next", serviceIntent(ACTION_NEXT, 3))
        }
        return builder.addAction(0, "Stop", stop).build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, MediaServerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is deprecated on 34+ but still the right choice below it.
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CastLite:server")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        // Keeps the CPU running so the server keeps sending bytes with the screen off.
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CastLite:server")?.apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        server?.stop()
        server = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        if (ServerMonitor.state.value is ServerState.Running) ServerMonitor.setState(ServerState.Stopped)
        Log.i(TAG, "Media server stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaServerService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.castlite.phone.action.STOP_SERVER"
        private const val ACTION_TOGGLE = "com.castlite.phone.action.TOGGLE"
        private const val ACTION_NEXT = "com.castlite.phone.action.NEXT"

        /** Safety net so a forgotten session can't drain the battery forever. */
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        /** Starts the server (no-op if already running). Call from the foreground UI. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MediaServerService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException if called from the background.
                Log.e(TAG, "Could not start service", e)
                ServerMonitor.setState(ServerState.Failed("Android blocked the server from starting: ${e.message}"))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaServerService::class.java))
        }
    }
}
