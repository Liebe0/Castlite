package com.castlite.tv.playback

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.castlite.protocol.Command
import com.castlite.protocol.MediaType
import com.castlite.protocol.QueueItem
import com.castlite.protocol.ReceiverState
import com.castlite.protocol.ReceiverStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Everything the TV screens render. */
data class TvUiState(
    val item: QueueItem? = null,
    val index: Int = -1,
    val count: Int = 0,
    val state: ReceiverState = ReceiverState.IDLE,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    /** Non-fatal problem, e.g. "video format unsupported, playing audio only". */
    val warning: String? = null,
    val overlayVisible: Boolean = false,
    val slideDurationMs: Long = 5_000,
    val volumePercent: Int? = null,
    /** IP of the phone that sent the last command. */
    val phone: String? = null,
    /** Shown on the idle screen after a session, e.g. "Finished playing". */
    val lastMessage: String? = null,
) {
    val isIdle: Boolean get() = item == null
    val isPlaying: Boolean get() = state == ReceiverState.PLAYING
}

/**
 * The receiver's brain: owns ExoPlayer, the queue and the photo slideshow, executes
 * commands from the phone and from the remote, and publishes [TvUiState].
 *
 * Must only be used on the main thread (ExoPlayer's application thread).
 */
@OptIn(UnstableApi::class) // decoder fallback is marked unstable but has been around for years
class PlaybackEngine(context: Context, private val scope: CoroutineScope) {

    private val audio = context.getSystemService(AudioManager::class.java)

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        // Falls back to another decoder (e.g. software) if the TV's primary one fails.
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .build()

    private val _ui = MutableStateFlow(TvUiState(volumePercent = readVolume()))
    val ui: StateFlow<TvUiState> = _ui.asStateFlow()

    private var cursor = QueueCursor()
    private var slideJob: Job? = null
    private var overlayJob: Job? = null
    private var tickerJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _ui.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_READY) {
                    _ui.update {
                        if (it.state == ReceiverState.LOADING) it.copy(state = if (player.playWhenReady) ReceiverState.PLAYING else ReceiverState.PAUSED) else it
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    advanceOrFinish()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    _ui.update { it.copy(state = ReceiverState.PLAYING) }
                } else if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                    _ui.update { it.copy(state = ReceiverState.PAUSED) }
                    showOverlay(autoHide = false)
                }
                syncPosition()
            }

            override fun onPlayerError(error: PlaybackException) {
                slideJob?.cancel()
                _ui.update { it.copy(state = ReceiverState.ERROR, error = PlaybackErrors.describe(error), isBuffering = false) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val videoBroken = tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                val audioBroken = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                _ui.update {
                    it.copy(
                        warning = when {
                            videoBroken && !audioBroken -> "This TV can't decode the video track — playing sound only."
                            audioBroken && !videoBroken -> "This TV can't decode the audio track — playing without sound."
                            else -> null
                        },
                    )
                }
            }
        })
    }

    // ---- Commands from the phone -------------------------------------------------

    fun execute(command: Command, client: String) {
        _ui.update { it.copy(phone = client) }
        when (command) {
            is Command.Load -> load(command)
            Command.Play -> play()
            Command.Pause -> pause()
            Command.Stop -> stop("Stopped from the phone")
            Command.Next -> check(next()) { "Already at the last item" }
            Command.Previous -> previous()
            is Command.Seek -> seekTo(command.positionMs)
            is Command.JumpTo -> jumpTo(command.index)
            is Command.SetVolume -> setVolume(command.percent)
        }
    }

    /** Snapshot for `/api/status`. Safe to call from any thread (reads the StateFlow). */
    fun status(): ReceiverStatus {
        val s = _ui.value
        return ReceiverStatus(
            state = s.state,
            index = s.index,
            count = s.count,
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            volumePercent = s.volumePercent,
            title = s.item?.title,
            type = s.item?.type,
            error = s.error ?: s.warning,
        )
    }

    private fun load(command: Command.Load) {
        require(command.items.isNotEmpty()) { "The queue is empty" }
        cursor = QueueCursor(command.items, command.startIndex)
        _ui.update { it.copy(slideDurationMs = command.slideDurationMs.coerceIn(1_000, 120_000), lastMessage = null) }
        startItem(command.startPositionMs)
        startTicker()
    }

    fun play() {
        val item = cursor.current ?: return
        if (item.type == MediaType.IMAGE) {
            _ui.update { it.copy(state = ReceiverState.PLAYING) }
            scheduleSlide()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (_ui.value.state == ReceiverState.ERROR) player.prepare()
            player.play()
        }
        showOverlay()
    }

    fun pause() {
        val item = cursor.current ?: return
        if (item.type == MediaType.IMAGE) {
            slideJob?.cancel()
            _ui.update { it.copy(state = ReceiverState.PAUSED) }
        } else {
            player.pause()
        }
        showOverlay(autoHide = false)
    }

    fun togglePlayPause() = if (_ui.value.state == ReceiverState.PLAYING) pause() else play()

    /** Ends the session and returns to the idle screen. */
    fun stop(message: String? = null) {
        slideJob?.cancel()
        tickerJob?.cancel()
        player.stop()
        player.clearMediaItems()
        cursor = QueueCursor()
        _ui.update {
            TvUiState(volumePercent = readVolume(), phone = it.phone, lastMessage = message, slideDurationMs = it.slideDurationMs)
        }
    }

    fun seekTo(positionMs: Long) {
        if (cursor.current?.type == MediaType.IMAGE) return
        player.seekTo(positionMs.coerceAtLeast(0))
        syncPosition()
        showOverlay()
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    fun next(): Boolean {
        if (!cursor.next()) return false
        startItem(0)
        return true
    }

    fun previous() {
        val pos = if (cursor.current?.type == MediaType.IMAGE) 0 else player.currentPosition
        cursor.previous(pos)
        startItem(0)
    }

    fun jumpTo(index: Int) {
        cursor.jumpTo(index)
        startItem(0)
    }

    fun setVolume(percent: Int) {
        val p = percent.coerceIn(0, 100)
        if (audio != null && !audio.isVolumeFixed) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (p / 100f * max).roundToInt(), AudioManager.FLAG_SHOW_UI)
        } else {
            // HDMI boxes often have fixed system volume: scale the player instead.
            player.volume = p / 100f
        }
        _ui.update { it.copy(volumePercent = p) }
    }

    // ---- Image callbacks from the UI -------------------------------------------------

    fun onImageShown(url: String) {
        if (cursor.current?.url != url) return
        _ui.update { if (it.state == ReceiverState.LOADING) it.copy(state = ReceiverState.PLAYING) else it }
        if (_ui.value.state == ReceiverState.PLAYING) scheduleSlide()
    }

    fun onImageFailed(url: String, reason: String?) {
        if (cursor.current?.url != url) return
        _ui.update {
            it.copy(state = ReceiverState.ERROR, error = "Couldn't show this photo" + (reason?.let { r -> " ($r)" } ?: "") + ".")
        }
        // Keep a slideshow going past a broken photo.
        scope.launch {
            delay(3_000)
            if (cursor.current?.url == url && _ui.value.state == ReceiverState.ERROR) advanceOrFinish()
        }
    }

    // ---- Remote control ---------------------------------------------------------------

    /** Handles a remote/D-pad key while something is on screen. Returns true if consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        if (_ui.value.isIdle) return false
        if (event.action != KeyEvent.ACTION_DOWN) {
            return event.keyCode in HANDLED_KEYS
        }
        val isImage = cursor.current?.type == MediaType.IMAGE
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK,
            -> if (_ui.value.state == ReceiverState.ERROR) next() else togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
            KeyEvent.KEYCODE_MEDIA_STOP -> stop("Stopped")
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                if (isImage || _ui.value.state == ReceiverState.ERROR) next() else seekBy(SEEK_STEP_MS)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                if (isImage) previous() else seekBy(-SEEK_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> previous()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> showOverlay()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (_ui.value.overlayVisible && _ui.value.state == ReceiverState.PLAYING) hideOverlay() else stop("Stopped")
            }
            else -> return false
        }
        return true
    }

    fun release() {
        slideJob?.cancel()
        tickerJob?.cancel()
        player.release()
    }

    // ---- Internals ------------------------------------------------------------------------

    private fun startItem(startPositionMs: Long) {
        val item = cursor.current ?: return
        slideJob?.cancel()
        _ui.update {
            it.copy(
                item = item,
                index = cursor.index,
                count = cursor.items.size,
                state = ReceiverState.LOADING,
                positionMs = 0,
                durationMs = item.durationMs,
                error = null,
                warning = null,
                lastMessage = null,
            )
        }
        if (item.type == MediaType.IMAGE) {
            // Photos are drawn by Compose (Coil); the image screen reports back via onImageShown().
            player.stop()
            player.clearMediaItems()
        } else {
            val mediaItem = MediaItem.Builder()
                .setUri(item.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                .build()
            player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = true
        }
        showOverlay()
    }

    private fun advanceOrFinish() {
        if (next()) return
        slideJob?.cancel()
        if (cursor.current?.type == MediaType.IMAGE) {
            // Leave the last photo up.
            _ui.update { it.copy(state = ReceiverState.ENDED) }
        } else {
            stop("Finished playing")
            // Back on the idle screen, but tell the phone the queue finished (vs. was stopped).
            _ui.update { it.copy(state = ReceiverState.ENDED) }
        }
    }

    private fun scheduleSlide() {
        slideJob?.cancel()
        val forIndex = cursor.index
        slideJob = scope.launch {
            delay(_ui.value.slideDurationMs)
            if (cursor.index == forIndex && _ui.value.state == ReceiverState.PLAYING) advanceOrFinish()
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                syncPosition()
                delay(500)
            }
        }
    }

    private fun syncPosition() {
        if (cursor.current == null || cursor.current?.type == MediaType.IMAGE) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        _ui.update {
            it.copy(
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = duration ?: it.durationMs,
                volumePercent = if (audio?.isVolumeFixed == false) readVolume() else it.volumePercent,
            )
        }
    }

    private fun showOverlay(autoHide: Boolean = true) {
        _ui.update { it.copy(overlayVisible = true) }
        overlayJob?.cancel()
        if (autoHide) {
            overlayJob = scope.launch {
                delay(OVERLAY_TIMEOUT_MS)
                if (_ui.value.state == ReceiverState.PLAYING) hideOverlay()
            }
        }
    }

    private fun hideOverlay() {
        overlayJob?.cancel()
        _ui.update { it.copy(overlayVisible = false) }
    }

    private fun readVolume(): Int? {
        val a = audio ?: return null
        if (a.isVolumeFixed) return null
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        return (a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    private companion object {
        const val SEEK_STEP_MS = 10_000L
        const val OVERLAY_TIMEOUT_MS = 4_000L
        val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        )
    }
}
