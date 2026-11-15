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

class CastLiteApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        CastManager.init(this)
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
