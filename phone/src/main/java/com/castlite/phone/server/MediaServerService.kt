package com.castlite.phone.server

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.castlite.phone.CastLiteApp
import com.castlite.phone.MainActivity
import com.castlite.phone.R
import com.castlite.phone.cast.CastManager
import com.castlite.phone.cast.CastMediaSession
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Foreground service that keeps casting alive while the phone is locked or another app
 * is in front. It owns:
 *  - the [MediaHttpServer] the TV streams from,
 *  - [KeepAwake]: wake + Wi-Fi locks while a TV depends on us (released when idle),
 *  - [CastMediaSession]: lock-screen controls and volume keys → TV volume,
 *  - the notification (media-style while casting, with Previous, Play/Pause, Next, Stop).
 *
 * Being a foreground service is what lets all of this keep running in Doze: Android
 * keeps network access and honours wake locks for apps with a foreground service.
 */
class MediaServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: MediaHttpServer? = null
    private lateinit var keepAwake: KeepAwake
    private lateinit var mediaSession: CastMediaSession
    private var notification: Notification? = null

    override fun onCreate() {
        super.onCreate()
        keepAwake = KeepAwake(this)
        mediaSession = CastMediaSession(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called within a few seconds of startForegroundService(). Re-use the
        // current notification so control taps don't flash a "Starting…" placeholder.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification ?: buildNotification(LanStatus.Disconnected, 0, null, null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )

        when (intent?.action) {
            ACTION_STOP -> {
                CastManager.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> CastManager.togglePlayPause()
            ACTION_NEXT -> CastManager.next()
            ACTION_PREVIOUS -> CastManager.previous()
        }

        if (server == null) {
            val started = startServer()
            if (started == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            server = started
            observe(started.listeningPort)
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
            ServerState.Failed("Couldn't open a network port (${lastError?.message}). Try again or restart the phone."),
        )
        return null
    }

    private fun observe(port: Int) {
        // Notification + media session follow the cast session and the network.
        scope.launch {
            combine(LanMonitor(this@MediaServerService).status, MediaLibrary.items, CastManager.session) { lan, items, session ->
                Triple(lan, items.size, session)
            }.collect { (lan, count, session) ->
                mediaSession.update(session)
                notify(buildNotification(lan, count, port, session))
            }
        }

        // A fresh server counts as activity: the user just picked media or started casting.
        keepAwake.onActivity()
        // Stay awake while a TV depends on us: a session starting or actively playing...
        scope.launch {
            CastManager.session
                .map { s -> s != null && s.status.state in AWAKE_STATES }
                .collect { busy -> if (busy) keepAwake.onActivity() }
        }
        // ...or the TV (or a browser) fetching media.
        scope.launch {
            ServerMonitor.requests.drop(1).collect { keepAwake.onActivity() }
        }
        // Renew while busy, release when idle (e.g. paused for 30 minutes).
        scope.launch {
            while (isActive) {
                delay(KeepAwake.CHECK_EVERY_MS)
                keepAwake.check()
            }
        }
    }

    @SuppressLint("MissingPermission") // Checked by NotificationManagerCompat; no-op if denied.
    private fun notify(n: Notification) {
        notification = n
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(lan: LanStatus, fileCount: Int, port: Int?, session: CastSession?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = session?.current?.displayName ?: "CastLite media server"
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
            // Show title and controls on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (session != null) {
            val playing = session.status.state == PlayerState.PLAYING || session.status.state == PlayerState.LOADING
            builder.addAction(R.drawable.ic_skip_previous, "Previous", serviceIntent(ACTION_PREVIOUS, 4))
            builder.addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                serviceIntent(ACTION_TOGGLE, 2),
            )
            builder.addAction(R.drawable.ic_skip_next, "Next", serviceIntent(ACTION_NEXT, 3))
            builder.addAction(R.drawable.ic_stop, "Stop", serviceIntent(ACTION_STOP, 1))
            // Media style = proper lock-screen player (Android 13+ draws it from the media session).
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.token)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        } else {
            builder.addAction(R.drawable.ic_stop, "Stop", serviceIntent(ACTION_STOP, 1))
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, MediaServerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope.cancel()
        server?.stop()
        server = null
        keepAwake.release()
        mediaSession.release()
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
        private const val ACTION_PREVIOUS = "com.castlite.phone.action.PREVIOUS"

        /** States in which the TV is actively relying on the phone. */
        private val AWAKE_STATES = setOf(PlayerState.CONNECTING, PlayerState.LOADING, PlayerState.PLAYING)

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
