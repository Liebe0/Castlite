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
import com.castlite.updater.AppTarget
import com.castlite.updater.AppUpdater
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CastLiteApp : Application(), SingletonImageLoader.Factory {

    /** Self-updater (GitHub releases). Installs only while the app is open and nothing is casting. */
    val updater: AppUpdater by lazy {
        AppUpdater(this, BuildConfig.UPDATE_MANIFEST_URL, AppTarget.PHONE).apply {
            canInstallNow = { isUiVisible && CastManager.session.value == null }
        }
    }

    /** True while an activity of ours is on screen (set by MainActivity). */
    @Volatile var isUiVisible = false
        set(value) {
            field = value
            if (value) updater.onForeground()
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        MediaLibrary.init(java.io.File(filesDir, "queue.json")) // the queue survives restarts
        CastManager.init(this)
        updater.startAutoChecks()
        // A ready update waits until casting stops.
        MainScope().launch {
            CastManager.session.map { it == null }.distinctUntilChanged().collect { idle -> if (idle) updater.installIfReady() }
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
