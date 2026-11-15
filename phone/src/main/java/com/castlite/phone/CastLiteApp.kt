package com.castlite.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.castlite.phone.cast.CastManager
import com.castlite.phone.media.MediaLibrary
import com.castlite.phone.mirror.MirrorManager
import com.castlite.phone.mirror.MirrorUiState
import com.castlite.updater.AppTarget
import com.castlite.updater.AppUpdater
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CastLiteApp : Application(), SingletonImageLoader.Factory {

    /**
     * Self-updater (GitHub releases). Installs only while nothing is casting or mirroring:
     * right away while the app is open, or (Android 12+, silently) a few minutes after
     * it was left.
     */
    val updater: AppUpdater by lazy {
        AppUpdater(this, BuildConfig.UPDATE_MANIFEST_URL, AppTarget.PHONE).apply {
            canInstallNow = { CastManager.session.value == null && MirrorManager.state.value is MirrorUiState.Idle }
        }
    }

    /** True while an activity of ours is on screen (set by MainActivity). */
    @Volatile var isUiVisible = false
        set(value) {
            val changed = field != value
            field = value
            if (value) updater.onForeground() else if (changed) updater.onBackground()
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        MediaLibrary.init(java.io.File(filesDir, "queue.json")) // the queue survives restarts
        CastManager.init(this)
        updater.startAutoChecks()
        // A ready update waits until casting and mirroring stop.
        MainScope().launch {
            combine(CastManager.session, MirrorManager.state) { session, mirror -> session == null && mirror is MirrorUiState.Idle }
                .distinctUntilChanged()
                .collect { idle -> if (idle) updater.installIfReady() }
        }
    }

    /** Coil image loader for queue thumbnails; the video decoder grabs a frame from videos. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()

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
