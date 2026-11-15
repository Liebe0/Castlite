package com.castlite.phone.cast

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.castlite.phone.data.AppPrefs
import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.MediaLibrary
import com.castlite.phone.net.LanMonitor
import com.castlite.phone.server.MediaServerService
import com.castlite.phone.server.ServerMonitor
import com.castlite.phone.server.ServerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The current casting session: which TV, what was sent, and what the TV reports. */
data class CastSession(
    val device: CastDevice,
    val queue: List<CastMedia>,
    val status: PlaybackStatus,
) {
    val current: CastMedia? get() = queue.getOrNull(status.index)
}

/**
 * App-wide casting coordinator (process singleton, shared by UI and the foreground
 * service). It picks the [CastController] for the selected TV, builds the media URLs
 * from the local server's address, and turns failures into readable messages.
 */
object CastManager {
    private const val TAG = "CastManager"

    // Application context only (set in CastLiteApp.onCreate), so this can't leak an Activity.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private lateinit var prefs: AppPrefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _selected = MutableStateFlow<CastDevice?>(null)
    val selectedDevice: StateFlow<CastDevice?> = _selected.asStateFlow()

    private val _session = MutableStateFlow<CastSession?>(null)
    val session: StateFlow<CastSession?> = _session.asStateFlow()

    private val _slideDurationMs = MutableStateFlow(5_000L)
    val slideDurationMs: StateFlow<Long> = _slideDurationMs.asStateFlow()

    /** One-off messages for a snackbar ("This is the last item", "Can't reach TV", ...). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var controller: CastController? = null
    private var statusJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
        prefs = AppPrefs(app)
        _selected.value = prefs.lastDevice
        _slideDurationMs.value = prefs.slideDurationMs
        watchWifi()
    }

    fun select(device: CastDevice) {
        if (_selected.value?.id != device.id) endSession(stopTv = false)
        _selected.value = device
        prefs.lastDevice = device
    }

    fun setSlideDuration(ms: Long) {
        _slideDurationMs.value = ms
        prefs.slideDurationMs = ms
    }

    /** Sends the whole queue to the selected TV, starting at [startIndex]. */
    fun cast(startIndex: Int = 0, startPositionMs: Long = 0) {
        val device = _selected.value ?: return emit("Choose a TV first")
        val items = MediaLibrary.items.value
        if (items.isEmpty()) return emit("Add some media first")

        scope.launch {
            releaseController()
            val index = startIndex.coerceIn(items.indices)
            _session.value = CastSession(device, items, PlaybackStatus(PlayerState.CONNECTING, index = index))
            try {
                val port = ensureServer()
                val ip = LanMonitor(app).current().ipAddress
                    ?: throw CastException("Your phone isn't on Wi-Fi. Connect to the same network as the TV.")
                val entries = items.map { QueueEntry(it, "http://$ip:$port${it.path}") }
                val c = CastControllerFactory.create(device, scope)
                controller = c
                statusJob = launch {
                    c.status.collect { st ->
                        // Keep CONNECTING visible until the controller reports something real.
                        if (st.state == PlayerState.IDLE && _session.value?.status?.state == PlayerState.CONNECTING) return@collect
                        _session.update { it?.copy(status = st) }
                    }
                }
                c.load(entries, index, startPositionMs, _slideDurationMs.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cast failed", e)
                _session.update { it?.copy(status = it.status.copy(state = PlayerState.ERROR, error = e.readable())) }
            }
        }
    }

    /** Re-sends the current item at its last position (after Wi-Fi came back, an error, ...). */
    fun retry() {
        val s = _session.value ?: return cast()
        cast(s.status.index, s.status.positionMs)
    }

    fun togglePlayPause() {
        val state = _session.value?.status?.state ?: return
        when (state) {
            PlayerState.PLAYING, PlayerState.LOADING -> command { pause() }
            PlayerState.PAUSED -> command { play() }
            PlayerState.ENDED, PlayerState.IDLE -> cast(0)
            PlayerState.ERROR -> retry()
            PlayerState.CONNECTING -> Unit
        }
    }

    fun seekTo(ms: Long) = command { seekTo(ms) }
    fun next() = command { next() }
    fun previous() = command { previous() }
    fun jumpTo(index: Int) = command { jumpTo(index) }
    fun setVolume(percent: Int) = command { setVolume(percent) }

    /** Stops playback on the TV and ends the session. */
    fun stop() = endSession(stopTv = true)

    private fun endSession(stopTv: Boolean) {
        val c = controller
        releaseController()
        _session.value = null
        if (stopTv && c != null) scope.launch { runCatching { c.stop() } }
    }

    private fun releaseController() {
        statusJob?.cancel()
        statusJob = null
        controller?.release()
        controller = null
    }

    private fun command(block: suspend CastController.() -> Unit) {
        val c = controller ?: return
        scope.launch {
            try {
                c.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(e.readable())
            }
        }
    }

    /** Starts the media server if needed and returns its port. */
    private suspend fun ensureServer(): Int {
        (ServerMonitor.state.value as? ServerState.Running)?.let { return it.port }
        MediaServerService.start(app)
        val state = withTimeoutOrNull(5_000) {
            ServerMonitor.state.first { it is ServerState.Running || it is ServerState.Failed }
        }
        return when (state) {
            is ServerState.Running -> state.port
            is ServerState.Failed -> throw CastException(state.message)
            else -> throw CastException("The media server didn't start. Try again.")
        }
    }

    /** Wi-Fi drop → the TV can't fetch media any more; say so instead of failing silently. */
    private fun watchWifi() {
        scope.launch {
            var wasConnected = true
            LanMonitor(app).status.collect { lan ->
                val s = _session.value
                if (!lan.isConnected && s != null && s.status.state != PlayerState.ERROR) {
                    _session.value = s.copy(
                        status = s.status.copy(state = PlayerState.ERROR, error = "Wi-Fi disconnected. Playback will stop when the TV runs out of buffered data."),
                    )
                }
                if (lan.isConnected && !wasConnected && s != null) emit("Wi-Fi is back. Tap Retry to resume.")
                wasConnected = lan.isConnected
            }
        }
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun Throwable.readable(): String = when (this) {
        is CastException -> message ?: "Casting failed"
        else -> "Casting failed: ${message ?: javaClass.simpleName}"
    }
}
