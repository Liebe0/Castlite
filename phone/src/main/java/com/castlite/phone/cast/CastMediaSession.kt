package com.castlite.phone.cast

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.media.AudioManager
import androidx.media.VolumeProviderCompat
import com.castlite.phone.media.MediaKind
import com.castlite.phone.ui.components.label

/**
 * A MediaSession that mirrors what's playing on the TV. It gives:
 *  - lock-screen / notification-shade media controls (play, pause, next, previous, seek),
 *  - Bluetooth/headset media buttons,
 *  - hardware volume keys that change the **TV's** volume (remote playback), also with
 *    the screen off.
 * Everything is forwarded to [CastManager].
 */
class CastMediaSession(context: Context) {

    val session = MediaSessionCompat(context.applicationContext, "CastLite").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = CastManager.play()
            override fun onPause() = CastManager.pause()
            override fun onSkipToNext() = CastManager.next()
            override fun onSkipToPrevious() = CastManager.previous()
            override fun onStop() = CastManager.stop()
            override fun onSeekTo(pos: Long) = CastManager.seekTo(pos)
        })
    }

    val token: MediaSessionCompat.Token get() = session.sessionToken

    private var volumeProvider: VolumeProviderCompat? = null
    private var lastMetadataKey: String? = null

    /** Publishes the current session state (null = nothing is being cast). */
    fun update(cast: CastSession?) {
        if (cast == null) {
            if (session.isActive) {
                session.setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0, 0f).build())
                session.isActive = false
                session.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
                volumeProvider = null
            }
            lastMetadataKey = null
            return
        }
        val status = cast.status
        val media = cast.current

        // Metadata only changes with the item, not every second.
        val key = "${cast.device.id}|${media?.id}|${status.durationMs}"
        if (key != lastMetadataKey) {
            lastMetadataKey = key
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media?.displayName ?: "CastLite")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "${media?.kind?.label() ?: "Media"} on ${cast.device.name}")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, cast.device.protocol.label)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (media?.kind == MediaKind.IMAGE) 0 else status.durationMs)
                    .build(),
            )
        }

        val state = when (status.state) {
            PlayerState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlayerState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlayerState.LOADING, PlayerState.CONNECTING -> PlaybackStateCompat.STATE_BUFFERING
            PlayerState.ERROR -> PlaybackStateCompat.STATE_ERROR
            PlayerState.ENDED, PlayerState.IDLE -> PlaybackStateCompat.STATE_STOPPED
        }
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (media?.kind != MediaKind.IMAGE && status.durationMs > 0) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        if (status.index + 1 < cast.queue.size) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (cast.queue.size > 1 || status.positionMs > 0) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, status.positionMs, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
                .apply { if (state == PlaybackStateCompat.STATE_ERROR) setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, status.error ?: "Error") }
                .build(),
        )

        updateVolume(status.volume)
        if (!session.isActive) session.isActive = true
    }

    /** Volume keys → TV volume, when the TV reports a volume we can set. */
    private fun updateVolume(volume: Int?) {
        if (volume == null) {
            if (volumeProvider != null) {
                session.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
                volumeProvider = null
            }
            return
        }
        val provider = volumeProvider
        if (provider == null) {
            val p = object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, 100, volume) {
                override fun onSetVolumeTo(volume: Int) {
                    currentVolume = volume.coerceIn(0, 100)
                    CastManager.setVolume(currentVolume)
                }

                override fun onAdjustVolume(direction: Int) {
                    if (direction == 0) return
                    currentVolume = (currentVolume + direction * VOLUME_STEP).coerceIn(0, 100)
                    CastManager.setVolume(currentVolume)
                }
            }
            volumeProvider = p
            session.setPlaybackToRemote(p)
        } else if (provider.currentVolume != volume) {
            provider.currentVolume = volume
        }
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private companion object {
        const val VOLUME_STEP = 4
    }
}
