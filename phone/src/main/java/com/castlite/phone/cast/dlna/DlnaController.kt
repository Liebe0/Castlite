package com.castlite.phone.cast.dlna

import com.castlite.phone.cast.CastController
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastException
import com.castlite.phone.cast.PlaybackStatus
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.cast.QueueEntry
import com.castlite.phone.cast.CastOptions
import com.castlite.phone.cast.DlnaEndpoints
import com.castlite.phone.diagnostics.Diag
import com.castlite.phone.media.Compatibility
import com.castlite.phone.media.MediaKind
import com.castlite.protocol.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Plays a queue on a DLNA/UPnP MediaRenderer (LG webOS, Samsung, Sony, Kodi, ...).
 *
 * A DLNA renderer only knows "the current URI", so the queue lives here on the phone:
 * we load one item at a time with SetAVTransportURI + Play, watch the transport state,
 * and load the next item when the current one finishes (or, for photos, after the
 * slideshow interval).
 *
 * All SOAP traffic goes through one single-threaded dispatcher: many TVs (LG included)
 * misbehave when they get overlapping requests, and it keeps the state logic simple.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DlnaController(
    override val device: CastDevice,
    private val scope: CoroutineScope,
    private val soap: SoapClient = SoapClient(),
    private val pollIntervalMs: Long = 1_000,
    private val retryDelayMs: Long = 700,
    /** If the TV hasn't started playing after this long, report it as unsupported. */
    private val startTimeoutMs: Long = 15_000,
) : CastController {

    private val endpoints = requireNotNull(device.dlna) { "Not a DLNA device" }
    private val serial = Dispatchers.IO.limitedParallelism(1)

    private val _status = MutableStateFlow(PlaybackStatus(PlayerState.IDLE))
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    // --- Queue state (only touched on [serial]) ---
    private var queue: List<QueueEntry> = emptyList()
    private var index = 0
    private var slideDurationMs = 5_000L
    private var loadedAt = 0L
    private var sawPlaying = false       // the current item has actually started
    private var stopRequested = false    // user pressed stop: don't auto-advance
    private var pausedByUser = false
    private var pendingSeekMs = 0L       // seek to apply once playback starts
    private var failures = 0
    private var polls = 0
    private var lastPlayingPositionMs = 0L // last position seen while PLAYING (0 = TV never reported one)
    private var repeat = RepeatMode.OFF
    private var lastTransportState = ""

    private var pollJob: Job? = null
    private var slideJob: Job? = null

    override suspend fun load(queue: List<QueueEntry>, startIndex: Int, startPositionMs: Long, options: CastOptions): Unit =
        onSerial {
            require(queue.isNotEmpty()) { "Nothing to cast" }
            this.queue = queue
            this.slideDurationMs = options.slideshow.durationMs
            repeat = options.repeat
            _status.update { it.copy(repeat = repeat) }
            loadIndex(startIndex.coerceIn(queue.indices), startPositionMs)
            startPolling()
        }

    override suspend fun play(): Unit = onSerial {
        pausedByUser = false
        if (current()?.media?.kind == MediaKind.IMAGE) {
            _status.update { it.copy(state = PlayerState.PLAYING) }
            scheduleSlide()
        } else {
            playWithRetry()
            _status.update { it.copy(state = PlayerState.PLAYING) }
        }
    }

    override suspend fun pause(): Unit = onSerial {
        pausedByUser = true
        slideJob?.cancel()
        if (current()?.media?.kind != MediaKind.IMAGE) {
            avTransport("Pause")
        }
        _status.update { it.copy(state = PlayerState.PAUSED) }
    }

    override suspend fun stop(): Unit = onSerial {
        stopRequested = true
        slideJob?.cancel()
        try {
            avTransport("Stop")
        } finally {
            _status.update { it.copy(state = PlayerState.IDLE, positionMs = 0) }
        }
    }

    override suspend fun seekTo(positionMs: Long): Unit = onSerial {
        if (current()?.media?.kind == MediaKind.IMAGE) return@onSerial
        avTransport("Seek", "Unit" to "REL_TIME", "Target" to UpnpTime.format(positionMs))
        _status.update { it.copy(positionMs = positionMs) }
    }

    override suspend fun setVolume(percent: Int): Unit = onSerial {
        val url = endpoints.renderingControlUrl ?: throw CastException("${device.name} doesn't allow volume control over DLNA")
        val value = percent.coerceIn(0, 100)
        call(url, RENDERING_CONTROL, "SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to value.toString())
        _status.update { it.copy(volume = value) }
    }

    override suspend fun next(): Unit = onSerial {
        when {
            index + 1 < queue.size -> loadIndex(index + 1, 0)
            repeat == RepeatMode.ALL -> loadIndex(0, 0)
            else -> throw CastException("This is the last item")
        }
    }

    override suspend fun setRepeat(mode: RepeatMode): Unit = onSerial {
        repeat = mode
        _status.update { it.copy(repeat = mode) }
    }

    override suspend fun setSubtitles(enabled: Boolean) {
        throw CastException("Subtitles can't be switched over DLNA — use the TV's remote")
    }

    override suspend fun previous(): Unit = onSerial {
        // Like most players: restart the current item unless we're near its beginning.
        if (index == 0 || (_status.value.positionMs > 5_000 && current()?.media?.kind != MediaKind.IMAGE)) {
            loadIndex(index, 0)
        } else {
            loadIndex(index - 1, 0)
        }
    }

    override suspend fun jumpTo(index: Int): Unit = onSerial {
        if (index !in queue.indices) throw CastException("No such item")
        loadIndex(index, 0)
    }

    override fun release() {
        pollJob?.cancel()
        slideJob?.cancel()
    }

    // ---------------------------------------------------------------------------

    private fun current(): QueueEntry? = queue.getOrNull(index)

    /** Loads queue item [i] on the TV and starts it. Runs on [serial]. */
    private suspend fun loadIndex(i: Int, startPositionMs: Long) {
        index = i
        sawPlaying = false
        stopRequested = false
        pausedByUser = false
        lastPlayingPositionMs = 0
        pendingSeekMs = startPositionMs
        loadedAt = System.currentTimeMillis()
        slideJob?.cancel()
        val entry = queue[i]
        _status.update {
            it.copy(state = PlayerState.LOADING, index = i, positionMs = 0, durationMs = entry.media.durationMs, error = null)
        }

        // Some renderers refuse a new URI while something is playing; stopping first is harmless.
        runCatching { avTransport("Stop") }

        val media = entry.media
        val metadata = Didl.build(media.displayName, entry.url, media.mimeType, media.kind, media.sizeBytes, media.durationMs, entry.subtitleUrl)
        Diag.log("dlna", "${device.name}: load #$i ${media.displayName} (${media.mimeType}) ${entry.url}")
        avTransport("SetAVTransportURI", "CurrentURI" to entry.url, "CurrentURIMetaData" to metadata)
        playWithRetry()

        if (media.kind == MediaKind.IMAGE) {
            sawPlaying = true
            _status.update { it.copy(state = PlayerState.PLAYING) }
            scheduleSlide()
        }
    }

    /** Play can fail with 701 "transition not available" while the TV is still buffering. */
    private suspend fun playWithRetry() {
        var attempt = 0
        while (true) {
            try {
                avTransport("Play", "Speed" to "1")
                return
            } catch (e: CastException) {
                if (e.upnpCode() != 701 || ++attempt >= 4) throw e
                delay(retryDelayMs)
            }
        }
    }

    private fun scheduleSlide() {
        slideJob?.cancel()
        val forIndex = index
        slideJob = scope.launch(serial) {
            delay(slideDurationMs)
            if (index == forIndex && !pausedByUser && !stopRequested) {
                slideJob = null // so loadIndex() doesn't cancel the coroutine that's running it
                try {
                    advanceOrEnd()
                } catch (e: CastException) {
                    _status.update { it.copy(state = PlayerState.ERROR, error = e.message) }
                }
            }
        }
    }

    private suspend fun advanceOrEnd() {
        when {
            repeat == RepeatMode.ONE -> loadIndex(index, 0)
            index + 1 < queue.size -> loadIndex(index + 1, 0)
            repeat == RepeatMode.ALL -> loadIndex(0, 0)
            else -> _status.update { it.copy(state = PlayerState.ENDED) }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(serial) {
            while (isActive) {
                delay(pollIntervalMs)
                try {
                    pollOnce()
                    failures = 0
                } catch (e: CastException) {
                    if (++failures >= 3) {
                        _status.update {
                            it.copy(state = PlayerState.ERROR, error = "Lost connection to ${device.name}. Check that the TV is on and on the same Wi-Fi.")
                        }
                    }
                }
            }
        }
    }

    /** Reads transport state + position and advances the queue when an item ends. */
    private suspend fun pollOnce() {
        val entry = current() ?: return
        val transport = avTransport("GetTransportInfo")
        val state = transport["CurrentTransportState"].orEmpty()
        if (state != lastTransportState) {
            Diag.log("dlna", "${device.name}: transport $lastTransportState → $state")
            lastTransportState = state
        }
        val isImage = entry.media.kind == MediaKind.IMAGE

        if (!isImage) {
            val position = avTransport("GetPositionInfo")
            val pos = UpnpTime.parse(position["RelTime"])
            val dur = UpnpTime.parse(position["TrackDuration"]).takeIf { it > 0 } ?: entry.media.durationMs
            _status.update { it.copy(positionMs = pos, durationMs = dur) }
        }
        if (polls++ % 5 == 0) refreshVolume()

        when (state) {
            "PLAYING" -> {
                sawPlaying = true
                if (!isImage) _status.value.positionMs.takeIf { it > 0 }?.let { lastPlayingPositionMs = it }
                if (pendingSeekMs > 0) {
                    val target = pendingSeekMs
                    pendingSeekMs = 0
                    runCatching { avTransport("Seek", "Unit" to "REL_TIME", "Target" to UpnpTime.format(target)) }
                }
                if (!isImage) _status.update { it.copy(state = PlayerState.PLAYING, error = null) }
            }
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> if (!isImage) _status.update { it.copy(state = PlayerState.PAUSED) }
            "TRANSITIONING" -> if (!sawPlaying) _status.update { it.copy(state = PlayerState.LOADING) }
            "STOPPED", "NO_MEDIA_PRESENT" -> when {
                isImage || stopRequested -> Unit
                sawPlaying && wasInterrupted(entry) -> _status.update {
                    // Stopped well before the end: the TV lost the stream (e.g. Wi-Fi) or was
                    // stopped with its remote. Don't skip ahead; let the user (or auto-resume) retry.
                    it.copy(
                        state = PlayerState.ERROR,
                        positionMs = lastPlayingPositionMs,
                        error = "Playback stopped on ${device.name} at ${UpnpTime.format(lastPlayingPositionMs)}. Tap Retry to continue.",
                    )
                }
                sawPlaying -> advanceOrEnd() // the item finished on its own
                System.currentTimeMillis() - loadedAt > startTimeoutMs -> _status.update {
                    it.copy(
                        state = PlayerState.ERROR,
                        error = "${device.name} didn't start \"${entry.media.displayName}\". " +
                            "The TV probably can't play this format (${entry.media.mimeType}).",
                    )
                }
            }
        }
    }

    /**
     * True if the TV stopped well before the end of the item. Only decidable when the TV
     * reports positions; renderers that don't are assumed to have finished.
     */
    private fun wasInterrupted(entry: QueueEntry): Boolean {
        val duration = _status.value.durationMs.takeIf { it > 0 } ?: entry.media.durationMs
        return lastPlayingPositionMs > 0 && duration > 0 && lastPlayingPositionMs < duration - END_TOLERANCE_MS
    }

    private suspend fun refreshVolume() {
        val url = endpoints.renderingControlUrl ?: return
        runCatching {
            val reply = call(url, RENDERING_CONTROL, "GetVolume", "InstanceID" to "0", "Channel" to "Master")
            reply["CurrentVolume"]?.toIntOrNull()?.let { v -> _status.update { it.copy(volume = v.coerceIn(0, 100)) } }
        }
    }

    private suspend fun avTransport(action: String, vararg args: Pair<String, String>): Map<String, String> =
        call(endpoints.avTransportUrl, AV_TRANSPORT, action, "InstanceID" to "0", *args)

    /** One SOAP call with errors translated into readable [CastException]s. */
    private suspend fun call(url: String, service: String, action: String, vararg args: Pair<String, String>): Map<String, String> =
        withContext(serial) {
            try {
                soap.call(url, service, action, args.toList())
            } catch (e: UpnpException) {
                Diag.log("dlna", "${device.name}: $action failed: UPnP ${e.code} ${e.description}")
                throw CastException(describe(action, e), e)
            } catch (e: ConnectException) {
                Diag.log("dlna", "${device.name}: $action: can't connect (${e.message})")
                throw CastException("Can't reach ${device.name}. Is it switched on and on the same Wi-Fi?", e)
            } catch (e: SocketTimeoutException) {
                throw CastException("${device.name} isn't responding.", e)
            } catch (e: UnknownHostException) {
                throw CastException("Can't find ${device.name} on the network.", e)
            } catch (e: IOException) {
                throw CastException("Network problem talking to ${device.name}: ${e.message}", e)
            }
        }

    private fun describe(action: String, e: UpnpException): String = when (e.code) {
        701 -> "The TV isn't ready for that yet. Try again in a moment."
        714, 715 -> "${device.name} doesn't support this file type (${current()?.media?.mimeType})."
        716 -> "${device.name} couldn't open the file. It may be blocked from reaching your phone."
        710 -> "Seeking isn't supported for this file on ${device.name}."
        else -> "${device.name} rejected $action (UPnP error ${e.code ?: "?"}${e.description?.let { ": $it" } ?: ""})."
    }

    /**
     * The UPnP error code behind a [CastException]. Walks the whole cause chain because
     * coroutines may re-wrap exceptions when they cross dispatchers (stack-trace recovery).
     */
    private fun Throwable.upnpCode(): Int? =
        generateSequence(this) { it.cause }.filterIsInstance<UpnpException>().firstOrNull()?.code

    private suspend fun <T> onSerial(block: suspend () -> T): T = withContext(serial) { block() }

    companion object {
        /**
         * Asks a DLNA TV which file types it accepts (ConnectionManager GetProtocolInfo
         * "Sink" list). Blocking; returns null if the TV doesn't say.
         */
        fun supportedTypes(endpoints: DlnaEndpoints, soap: SoapClient = SoapClient()): Set<String>? {
            val url = endpoints.connectionManagerUrl ?: return null
            return try {
                val reply = soap.call(url, CONNECTION_MANAGER, "GetProtocolInfo", emptyList())
                Compatibility.parseSinkProtocolInfo(reply["Sink"].orEmpty()).takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Diag.log("dlna", "GetProtocolInfo failed: ${e.message}")
                null
            }
        }

        const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

        /** Stopping within this much of the end counts as "finished". */
        private const val END_TOLERANCE_MS = 15_000L
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    }
}
