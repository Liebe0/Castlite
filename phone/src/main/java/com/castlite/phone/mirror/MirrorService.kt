package com.castlite.phone.mirror

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.castlite.phone.CastLiteApp
import com.castlite.phone.MainActivity
import com.castlite.phone.R
import com.castlite.phone.diagnostics.Diag

/**
 * Screen mirroring to the CastLite TV app. Android requires screen capture to run in a
 * foreground service of type "mediaProjection", started *before* the capture begins.
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var session: MirrorSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: "TV"
        val wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (wantAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(name), type)

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        val host = intent.getStringExtra(EXTRA_HOST)
        if (data == null || host == null) {
            MirrorManager.failed("Mirroring couldn't start")
            stopSelf()
            return START_NOT_STICKY
        }
        session?.stop()
        projection?.stop()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            MirrorManager.failed("Android didn't allow screen capture")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Diag.log("mirror", "Capture stopped by the system")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        Diag.log("mirror", "Mirroring to $name ($host), audio=$wantAudio")
        session = MirrorSession(this, p, host, wantAudio, object : MirrorSession.Listener {
            override fun onStarted(width: Int, height: Int, audio: Boolean) = MirrorManager.active(name, width, height, audio)
            override fun onEnded(error: String?) {
                Handler(Looper.getMainLooper()).post {
                    MirrorManager.stopped(error)
                    stopSelf()
                }
            }
        }).also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        projection?.stop()
        projection = null
        if (MirrorManager.state.value !is MirrorUiState.Idle) MirrorManager.stopped(null)
        super.onDestroy()
    }

    private fun notification(tv: String): Notification {
        val stop = PendingIntent.getService(
            this, 10, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CastLiteApp.CHANNEL_CASTING)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle("Mirroring your screen")
            .setContentText("Showing this phone on $tv")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_NAME = "name"
        const val EXTRA_AUDIO = "audio"
        private const val ACTION_STOP = "com.castlite.phone.action.STOP_MIRROR"
        private const val NOTIFICATION_ID = 2
    }
}
