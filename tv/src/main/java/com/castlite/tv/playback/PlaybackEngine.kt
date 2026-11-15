package com.castlite.tv.playback

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.castlite.protocol.Command
import com.castlite.protocol.MediaType
import com.castlite.protocol.QueueItem
import com.castlite.protocol.ReceiverState
import com.castlite.protocol.ReceiverStatus
import com.castlite.protocol.RepeatMode
import com.castlite.protocol.SlideshowOptions
import com.castlite.tv.TvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.roundToInt

/** Everything the TV screens render. */
data class TvUiState(
    val item: QueueItem? = null,
    /** Index in the phone's full queue. */
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
    val slideshow: SlideshowOptions = SlideshowOptions(),
    val repeat: RepeatMode = RepeatMode.OFF,
    /** null = the current item has no subtitles. */
    val subtitlesEnabled: Boolean? = null,
    /** Title of the song playing behind a slideshow. */
    val musicTitle: String? = null,
    val volumePercent: Int? = null,
    /** IP of the phone that sent the last command. */
    val phone: String? = null,
    /** Shown on the idle screen after a session, e.g. "Finished playing". */
    val lastMessage: String? = null,
    /** Bumped to make the photo screen request the current photo again. */
    val imageAttempt: Int = 0,
) {
    val isIdle: Boolean get() = item == null
    val isPlaying: Boolean get() = state == ReceiverState.PLAYING
    val slideDurationMs: Long get() = slideshow.durationMs

    /** Video and photos need the TV screen; songs can keep playing in the background. */
    val needsScreen: Boolean get() = item != null && item.type != MediaType.AUDIO
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
        // Buffer up to 2 minutes ahead (default ~50 s) so short gaps — the phone locking,
        // Wi-Fi power-save, roaming — never reach the screen.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
                .build(),
        )
        // Keep retrying the phone for a while instead of failing on the first timeouts.
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setLoadErrorHandlingPolicy(PatientLoadErrorPolicy()))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .build()

    private val _ui = MutableStateFlow(TvUiState(volumePercent = readVolume()))
    val ui: StateFlow<TvUiState> = _ui.asStateFlow()

    private var plan = QueuePlan(emptyList(), emptyList(), emptyList())
    private var cursor = QueueCursor()
    /** Remembered across items: the viewer turned subtitles off/on. */
    private var subtitlesWanted = true
    private var slideJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    /** Times the current item stopped early and was continued (see resumeIfEndedEarly). */
    private var earlyEndRetries = 0
    private var overlayJob: Job? = null
    private var tickerJob: Job? = null

    init {
        // Downloads that fail and get retried are otherwise invisible: note them for diagnostics.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                TvLog.log("load", "Download from byte ${loadEventInfo.dataSpec.position} failed: ${error.javaClass.simpleName} ${error.message}")
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (plan.musicMode) return // background music: the photos drive the state
                _ui.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_READY) {
                    reconnectAttempts = 0
                    _ui.update {
                        val cleared = if (it.warning == RECONNECTING) it.copy(warning = null) else it
                        if (cleared.state == ReceiverState.LOADING) cleared.copy(state = if (player.playWhenReady) ReceiverState.PLAYING else ReceiverState.PAUSED) else cleared
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    if (resumeIfEndedEarly()) return
                    advanceOrFinish()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (plan.musicMode) return
                if (isPlaying) {
                    _ui.update { it.copy(state = ReceiverState.PLAYING) }
                } else if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                    _ui.update { it.copy(state = ReceiverState.PAUSED) }
                    showOverlay(autoHide = false)
                }
                syncPosition()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    TvLog.log("seek", "Player moved ${oldPosition.positionMs} → ${newPosition.positionMs} ms (reason $reason, seekable=${player.isCurrentMediaItemSeekable})")
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (plan.musicMode) _ui.update { it.copy(musicTitle = mediaItem?.mediaMetadata?.title?.toString()) }
            }

            override fun onPlayerError(error: PlaybackException) {
                TvLog.log("player", "Error ${error.errorCodeName}: ${error.message} (cause: ${error.cause?.message})")
                if (plan.musicMode) {
                    // A broken song shouldn't stop the slideshow.
                    _ui.update { it.copy(warning = "Background music stopped: ${PlaybackErrors.describe(error)}", musicTitle = null) }
                    return
                }
                slideJob?.cancel()
                // The phone may just be briefly unreachable (screen off, Wi-Fi power-save,
                // roaming): keep trying for a while before showing an error.
                if (PlaybackErrors.isNetwork(error) && reconnectAttempts < RECONNECT_DELAYS_MS.size) {
                    val wait = RECONNECT_DELAYS_MS[reconnectAttempts++]
                    _ui.update { it.copy(state = ReceiverState.LOADING, warning = RECONNECTING, isBuffering = true) }
                    showOverlay(autoHide = false)
                    reconnectJob?.cancel()
                    reconnectJob = scope.launch {
                        delay(wait)
                        // prepare() after an error resumes from the same position.
                        player.prepare()
                        player.playWhenReady = true
                    }
                    return
                }
                _ui.update { it.copy(state = ReceiverState.ERROR, error = PlaybackErrors.describe(error), isBuffering = false, warning = null) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (plan.musicMode) return
                val videoBroken = tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                val audioBroken = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                if (videoBroken || audioBroken) TvLog.log("player", "Unsupported track(s): video=$videoBroken audio=$audioBroken")
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
        TvLog.log("cmd", "${command::class.simpleName} from $client")
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
            is Command.SetRepeat -> _ui.update { it.copy(repeat = command.mode) }
            is Command.SetSubtitles -> setSubtitles(command.enabled)
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
            repeat = s.repeat,
            subtitlesEnabled = s.subtitlesEnabled,
        )
    }

    private fun load(command: Command.Load) {
        require(command.items.isNotEmpty()) { "The queue is empty" }
        val options = command.slideshow ?: SlideshowOptions(durationMs = command.slideDurationMs)
        plan = QueuePlan.of(command.items, options.music)
        val start = plan.shownPosition(command.startIndex) ?: 0
        cursor = QueueCursor(plan.shown, start)
        _ui.update {
            it.copy(
                slideshow = options.copy(durationMs = options.durationMs.coerceIn(1_000, 120_000)),
                repeat = command.repeat,
                count = command.items.size,
                lastMessage = null,
                musicTitle = null,
            )
        }
        TvLog.log("queue", "Loaded ${command.items.size} item(s), start=${command.startIndex}, repeat=${command.repeat}, music=${plan.musicMode}")
        if (plan.musicMode) startMusic()
        startItem(command.startPositionMs)
        startTicker()
    }

    fun play() {
        val item = cursor.current ?: return
        if (item.type == MediaType.IMAGE) {
            _ui.update { it.copy(state = ReceiverState.PLAYING) }
            if (plan.musicMode) player.play()
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
            if (plan.musicMode) player.pause()
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
        reconnectJob?.cancel()
        reconnectAttempts = 0
        tickerJob?.cancel()
        player.stop()
        player.clearMediaItems()
        player.repeatMode = Player.REPEAT_MODE_OFF
        plan = QueuePlan(emptyList(), emptyList(), emptyList())
        cursor = QueueCursor()
        if (message != null) TvLog.log("queue", "Stopped: $message")
        _ui.update {
            TvUiState(volumePercent = readVolume(), phone = it.phone, lastMessage = message, slideshow = it.slideshow, repeat = it.repeat)
        }
    }

    /** The TV app went to the background: stop what needs the screen, keep music playing. */
    fun onScreenHidden() {
        if (_ui.value.needsScreen) stop("Stopped because CastLite was closed on the TV")
    }

    fun seekTo(positionMs: Long) {
        if (cursor.current?.type == MediaType.IMAGE) return
        val target = positionMs.coerceAtLeast(0)
        TvLog.log("seek", "Seek to $target ms from ${player.currentPosition} ms (duration ${player.duration}, state ${player.playbackState}, seekable ${player.isCurrentMediaItemSeekable})")
        // ExoPlayer "seeks" an unseekable stream by restarting it from 0:00. Keep playing instead.
        if (!player.currentTimeline.isEmpty && !player.isCurrentMediaItemSeekable) {
            TvLog.log("seek", "Ignored: this stream can't be seeked")
            _ui.update { it.copy(warning = "This video can't be skipped through (the file has no index).") }
            showOverlay()
            return
        }
        earlyEndRetries = 0
        player.seekTo(target)
        syncPosition()
        showOverlay()
    }

    /**
     * ExoPlayer reached the end of the stream well before the end of the video: the phone
     * stopped sending (connection cut, phone busy) rather than the video finishing. Carry on
     * from there instead of moving on — with repeat on, that would restart at 0:00. A file
     * whose real length is shorter than it claims ends for good after a couple of tries.
     */
    private fun resumeIfEndedEarly(): Boolean {
        val duration = player.duration
        val position = player.currentPosition
        if (!EndCheck.isPremature(position, duration) || earlyEndRetries >= EARLY_END_RETRIES) return false
        earlyEndRetries++
        TvLog.log("player", "Stream ended at $position ms of $duration ms — continuing from there (try $earlyEndRetries)")
        player.seekTo(position)
        player.playWhenReady = true
        return true
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    /** Next item; with repeat-all it wraps around. Returns false at the end of the queue. */
    fun next(): Boolean {
        if (!cursor.next()) {
            if (_ui.value.repeat != RepeatMode.ALL || cursor.items.isEmpty()) return false
            cursor.jumpTo(0)
        }
        startItem(0)
        return true
    }

    fun previous() {
        val pos = if (cursor.current?.type == MediaType.IMAGE) 0 else player.currentPosition
        cursor.previous(pos)
        startItem(0)
    }

    fun jumpTo(fullQueueIndex: Int) {
        val position = plan.shownPosition(fullQueueIndex)
            ?: throw IllegalArgumentException("That song is the slideshow's background music")
        cursor.jumpTo(position)
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

    fun setSubtitles(enabled: Boolean) {
        subtitlesWanted = enabled
        applySubtitlePreference()
        _ui.update { if (it.subtitlesEnabled != null) it.copy(subtitlesEnabled = enabled) else it }
        showOverlay()
    }

    private fun applySubtitlePreference() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesWanted)
            .build()
    }

    // ---- Image callbacks from the UI -------------------------------------------------

    fun onImageShown(url: String) {
        if (cursor.current?.url != url) return
        reconnectAttempts = 0
        _ui.update { if (it.warning == RECONNECTING) it.copy(warning = null) else it }
        _ui.update { if (it.state == ReceiverState.LOADING) it.copy(state = ReceiverState.PLAYING) else it }
        if (_ui.value.state == ReceiverState.PLAYING) scheduleSlide()
    }

    fun onImageFailed(url: String, reason: String?) {
        if (cursor.current?.url != url) return
        // Probably a short network hiccup: ask for the same photo again a few times.
        if (reconnectAttempts < IMAGE_RETRY_DELAYS_MS.size) {
            val wait = IMAGE_RETRY_DELAYS_MS[reconnectAttempts++]
            slideJob?.cancel()
            _ui.update { it.copy(warning = RECONNECTING) }
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(wait)
                if (cursor.current?.url == url) _ui.update { it.copy(imageAttempt = it.imageAttempt + 1) }
            }
            return
        }
        TvLog.log("photo", "Couldn't load $url: $reason")
        _ui.update {
            it.copy(state = ReceiverState.ERROR, warning = null, error = "Couldn't show this photo" + (reason?.let { r -> " ($r)" } ?: "") + ".")
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
            KeyEvent.KEYCODE_CAPTIONS -> if (_ui.value.subtitlesEnabled != null) setSubtitles(!subtitlesWanted)
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

    private fun startMusic() {
        player.setMediaItems(plan.music.map { mediaItemFor(it) })
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
        player.playWhenReady = true
        _ui.update { it.copy(musicTitle = plan.music.first().title) }
    }

    private fun mediaItemFor(item: QueueItem): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(item.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
        // Streaming playlists from web links need their type spelled out.
        when {
            item.mimeType.contains("mpegurl", ignoreCase = true) || item.url.substringBefore('?').endsWith(".m3u8") ->
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val subUrl = item.subtitleUrl
        if (subUrl != null) {
            val mime = when {
                item.subtitleMimeType?.contains("vtt") == true || subUrl.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                        .setMimeType(mime)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .setLanguage("und")
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    private fun startItem(startPositionMs: Long) {
        val item = cursor.current ?: return
        slideJob?.cancel()
        reconnectJob?.cancel()
        reconnectAttempts = 0
        earlyEndRetries = 0
        TvLog.log("queue", "Start #${cursor.index} \"${item.title}\" at $startPositionMs ms")
        _ui.update {
            it.copy(
                item = item,
                index = plan.fullIndex.getOrElse(cursor.index) { cursor.index },
                state = ReceiverState.LOADING,
                positionMs = 0,
                durationMs = item.durationMs,
                error = null,
                warning = null,
                lastMessage = null,
                subtitlesEnabled = if (item.subtitleUrl != null) subtitlesWanted else null,
            )
        }
        if (item.type == MediaType.IMAGE) {
            // Photos are drawn by Compose (Coil); the photo screen reports back via onImageShown().
            if (!plan.musicMode) {
                player.stop()
                player.clearMediaItems()
            }
        } else {
            applySubtitlePreference()
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setMediaItem(mediaItemFor(item), startPositionMs.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = true
        }
        showOverlay()
    }

    private fun advanceOrFinish() {
        val repeat = _ui.value.repeat
        if (repeat == RepeatMode.ONE) {
            startItem(0)
            return
        }
        if (next()) return
        slideJob?.cancel()
        if (cursor.current?.type == MediaType.IMAGE) {
            // Leave the last photo up (music keeps playing).
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
        const val EARLY_END_RETRIES = 2
        const val OVERLAY_TIMEOUT_MS = 4_000L
        const val RECONNECTING = "Reconnecting to your phone…"
        /** After ExoPlayer's own retries give up, try again this many times (~1 min more). */
        val RECONNECT_DELAYS_MS = longArrayOf(3_000, 6_000, 10_000, 15_000, 20_000)
        val IMAGE_RETRY_DELAYS_MS = longArrayOf(1_500, 3_000, 5_000, 8_000)
        val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        )
    }
}
