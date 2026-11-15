package com.castlite.phone.cast

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.castlite.phone.cast.dlna.DlnaController
import com.castlite.phone.data.AppPrefs
import com.castlite.phone.data.ResumeStore
import com.castlite.phone.diagnostics.Diag
import com.castlite.phone.media.MediaKind
import com.castlite.phone.media.TvSupport
import com.castlite.phone.net.WakeOnLan
import com.castlite.protocol.ReceiverClient
import com.castlite.protocol.RepeatMode
import com.castlite.protocol.SlideshowOptions
import kotlinx.coroutines.withContext
import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.MediaLibrary
import com.castlite.phone.net.LanMonitor
import com.castlite.phone.net.LanStatus
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** When to re-send the current item after a network change. Pure logic, unit-tested. */
object AutoResume {
    /** True when we went from no network to a network, or our IP address changed. */
    fun shouldCheck(previous: LanStatus?, current: LanStatus): Boolean =
        previous != null && current.isConnected && (!previous.isConnected || addressChanged(previous, current))

    fun addressChanged(previous: LanStatus?, current: LanStatus): Boolean =
        previous?.ipAddress != null && current.ipAddress != null && previous.ipAddress != current.ipAddress

    /**
     * A new address breaks every media URL the TV has, so always resend. Otherwise only
     * resend if the TV actually lost the stream (it may have played on from its buffer).
     */
    fun shouldResume(addressChanged: Boolean, state: PlayerState): Boolean =
        addressChanged || state == PlayerState.ERROR
}

/** The current casting session: which TV(s), what was sent, and what the TV reports. */
data class CastSession(
    /** The TV whose status is shown (and that the volume keys control). */
    val device: CastDevice,
    val queue: List<CastMedia>,
    val status: PlaybackStatus,
    /** Set while the phone has no Wi-Fi (kept apart from [status], which is what the TV reports). */
    val networkProblem: String? = null,
    /** Other TVs playing the same queue at the same time ("party mode"). */
    val extraDevices: List<CastDevice> = emptyList(),
) {
    val current: CastMedia? get() = queue.getOrNull(status.index)

    /** The message to show, if anything is wrong. */
    val problem: String? get() = networkProblem ?: status.error.takeIf { status.state == PlayerState.ERROR }

    /** Something the TV says while still playing (e.g. "can't be skipped through"). */
    val notice: String? get() = status.error.takeIf { problem == null && status.state != PlayerState.ERROR }

    /** "LG TV" or "LG TV + 2 more". */
    val devicesLabel: String get() = if (extraDevices.isEmpty()) device.name else "${device.name} + ${extraDevices.size} more"
}

/**
 * App-wide casting coordinator (process singleton, shared by UI, the foreground service,
 * the notification, the widget and the Quick Settings tile). It creates a
 * [CastController] for each TV, builds the media URLs from the local server's address,
 * and turns failures into readable messages.
 */
object CastManager {
    private const val TAG = "CastManager"
    private const val AUTO_RESUME_DELAY_MS = 3_000L
    private const val WAKE_WAIT_MS = 12_000L

    // Application context only (set in CastLiteApp.onCreate), so this can't leak an Activity.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private lateinit var prefs: AppPrefs
    private lateinit var resume: ResumeStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _selected = MutableStateFlow<CastDevice?>(null)
    val selectedDevice: StateFlow<CastDevice?> = _selected.asStateFlow()

    /** Extra TVs that play along with the selected one. */
    private val _extraDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    val extraDevices: StateFlow<List<CastDevice>> = _extraDevices.asStateFlow()

    private val _session = MutableStateFlow<CastSession?>(null)
    val session: StateFlow<CastSession?> = _session.asStateFlow()

    private val _slideshow = MutableStateFlow(SlideshowOptions())
    val slideshow: StateFlow<SlideshowOptions> = _slideshow.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    /** What each TV can play (by device id), for "may not play" warnings. */
    private val _support = MutableStateFlow<Map<String, TvSupport>>(emptyMap())
    val support: StateFlow<Map<String, TvSupport>> = _support.asStateFlow()

    /** Device id → MAC address for Wake-on-LAN. */
    private val _macs = MutableStateFlow<Map<String, String>>(emptyMap())
    val macs: StateFlow<Map<String, String>> = _macs.asStateFlow()

    /** One-off messages for a snackbar ("This is the last item", "Can't reach TV", ...). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var controller: CastController? = null
    private var extraControllers: List<CastController> = emptyList()
    private var statusJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
        prefs = AppPrefs(app)
        resume = ResumeStore(app)
        _selected.value = prefs.lastDevice
        _slideshow.value = prefs.slideshow
        _repeat.value = prefs.repeat
        _macs.value = prefs.macs
        watchWifi()
        watchQueue()
        _selected.value?.let { refreshSupport(it) }
    }

    /** Removing the file that's on the TV (or clearing the queue) stops casting it. */
    private fun watchQueue() {
        scope.launch {
            MediaLibrary.items.collect { items ->
                val s = _session.value ?: return@collect
                val current = s.current ?: return@collect
                if (s.status.state == PlayerState.CONNECTING) return@collect
                if (items.none { it.id == current.id }) {
                    Diag.log("cast", "${current.displayName} was removed from the queue — stopping")
                    emit("Stopped casting ${current.displayName}")
                    endSession(stopTv = true)
                }
            }
        }
    }

    /** True while a TV is actually using the phone (used when the app is closed). */
    val isBusy: Boolean
        get() = _session.value?.status?.state.let {
            it == PlayerState.CONNECTING || it == PlayerState.LOADING || it == PlayerState.PLAYING || it == PlayerState.PAUSED
        }

    fun select(device: CastDevice) {
        if (_selected.value?.id != device.id) endSession(stopTv = false)
        _selected.value = device
        _extraDevices.value = _extraDevices.value.filterNot { it.id == device.id }
        prefs.lastDevice = device
        Diag.log("cast", "Selected ${device.name} (${device.protocol}, ${device.host})")
        refreshSupport(device)
    }

    /** Adds/removes a TV that should play along with the selected one. */
    fun toggleExtraDevice(device: CastDevice) {
        if (device.id == _selected.value?.id) return
        _extraDevices.value = if (_extraDevices.value.any { it.id == device.id }) {
            _extraDevices.value.filterNot { it.id == device.id }
        } else {
            _extraDevices.value + device
        }
        if (_extraDevices.value.any { it.id == device.id }) refreshSupport(device)
    }

    fun setSlideshow(options: SlideshowOptions) {
        _slideshow.value = options
        prefs.slideshow = options
    }

    fun setRepeat(mode: RepeatMode) {
        _repeat.value = mode
        prefs.repeat = mode
        if (controller != null) command(allTvs = true) { setRepeat(mode) }
    }

    fun cycleRepeat() = setRepeat(RepeatMode.entries[(_repeat.value.ordinal + 1) % RepeatMode.entries.size])

    fun setSubtitles(enabled: Boolean) = command { setSubtitles(enabled) }

    fun setMac(device: CastDevice, mac: String?) {
        val updated = if (mac.isNullOrBlank()) _macs.value - device.id else _macs.value + (device.id to mac)
        _macs.value = updated
        prefs.macs = updated
    }

    /** Sends a Wake-on-LAN packet to [device] (needs its MAC address). */
    fun wake(device: CastDevice) {
        val mac = _macs.value[device.id] ?: return emit("Add the TV's MAC address first (Choose a TV → ⋮ → Power on).")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WakeOnLan.send(mac, LanMonitor(app).current().ipAddress) }
                Diag.log("wol", "Sent magic packet to ${device.name} ($mac)")
                emit("Turning on ${device.name}… give it about 10 seconds")
            } catch (e: Exception) {
                emit("Couldn't send the power-on signal: ${e.message}")
            }
        }
    }

    /** Saved position for "Resume from …?" (0 = none). */
    fun resumePositionFor(media: CastMedia): Long = if (media.isRemote) 0 else resume.get(media.contentUri)

    /** Asks a TV what it can play (decoders for the CastLite app, accepted types for DLNA). */
    fun refreshSupport(device: CastDevice) {
        scope.launch {
            val support = withContext(Dispatchers.IO) {
                runCatching {
                    when (device.protocol) {
                        CastProtocol.CASTLITE -> ReceiverClient(device.host, device.receiverPort, 2_500, 3_000).info().capabilities?.let { c ->
                            TvSupport(videoCodecs = c.videoCodecs.toSet(), audioCodecs = c.audioCodecs.toSet(), hevcMain10 = c.hevcMain10, maxVideoHeight = c.maxVideoHeight)
                        }
                        CastProtocol.DLNA -> device.dlna?.let { e -> TvSupport(containers = DlnaController.supportedTypes(e), isDlna = true) }
                        CastProtocol.GOOGLE_CAST -> null
                    }
                }.getOrNull()
            } ?: return@launch
            Diag.log("cast", "${device.name} supports: $support")
            _support.update { it + (device.id to support) }
        }
    }

    /** Sends the whole queue to the selected TV(s), starting at [startIndex]. */
    fun cast(startIndex: Int = 0, startPositionMs: Long = 0) {
        val device = _selected.value ?: return emit("Choose a TV first")
        val items = MediaLibrary.items.value
        if (items.isEmpty()) return emit("Add some media first")
        val extras = _extraDevices.value

        scope.launch {
            releaseController()
            val index = startIndex.coerceIn(items.indices)
            _session.value = CastSession(device, items, PlaybackStatus(PlayerState.CONNECTING, index = index), extraDevices = extras)
            try {
                val port = ensureServer()
                val ip = LanMonitor(app).current().ipAddress
                    ?: throw CastException("Your phone isn't on Wi-Fi. Connect to the same network as the TV.")
                val entries = items.map { m ->
                    QueueEntry(
                        media = m,
                        url = if (m.isRemote) m.contentUri else "http://$ip:$port${m.path}",
                        subtitleUrl = m.subtitlePath?.let { "http://$ip:$port$it" },
                    )
                }
                val options = CastOptions(_repeat.value, _slideshow.value)
                Diag.log("cast", "Cast ${items.size} item(s) from #$index to ${device.name}${if (extras.isEmpty()) "" else " + ${extras.map { it.name }}"}")

                // Each TV gets URLs it can reach (TVs on another router need a tunnel or a router port).
                val mainEntries = routed(device, entries, ip, port)

                val c = CastControllerFactory.create(device, scope)
                controller = c
                var lastIndex = index
                var lastSave = 0L
                val endPolicy = SessionEndPolicy()
                statusJob = launch {
                    c.status.collect { st ->
                        // Keep CONNECTING visible until the controller reports something real.
                        if (st.state == PlayerState.IDLE && _session.value?.status?.state == PlayerState.CONNECTING) return@collect
                        // The TV stopped or finished: end the session so no stale mini player stays behind.
                        if (endPolicy.onStatus(st.state, System.currentTimeMillis()) == SessionEndPolicy.Verdict.END_NOW) {
                            Diag.log("cast", "TV went idle — ending the session")
                            scope.launch { endSession(stopTv = false) }
                            return@collect
                        }
                        if (st.state == PlayerState.ENDED) scheduleEndCheck(c, endPolicy)
                        val session = _session.value
                        // Resume positions: remember where long videos/songs are; forget finished ones.
                        if (session != null) {
                            if (st.index != lastIndex && st.state != PlayerState.ERROR) session.queue.getOrNull(lastIndex)?.let { resume.clear(it.contentUri) }
                            val cur = session.queue.getOrNull(st.index)
                            if (cur != null && !cur.isRemote && cur.kind != MediaKind.IMAGE) {
                                val now = System.currentTimeMillis()
                                if (st.state == PlayerState.ENDED) resume.clear(cur.contentUri)
                                else if (now - lastSave > 5_000 && (st.state == PlayerState.PLAYING || st.state == PlayerState.PAUSED)) {
                                    resume.save(cur.contentUri, st.positionMs, st.durationMs)
                                    lastSave = now
                                }
                            }
                        }
                        lastIndex = st.index
                        _session.update { it?.copy(status = st) }
                    }
                }
                // Other TVs start in parallel; their problems don't stop the main one.
                extraControllers = extras.map { CastControllerFactory.create(it, scope) }
                extraControllers.forEach { ec ->
                    launch {
                        try {
                            ec.load(routed(ec.device, entries, ip, port), index, startPositionMs, options)
                        } catch (e: Exception) {
                            emit("${ec.device.name}: ${e.readable()}")
                        }
                    }
                }
                loadWithWake(c, mainEntries, index, startPositionMs, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cast failed", e)
                Diag.log("cast", "Cast failed: ${e.readable()}")
                _session.update { it?.copy(status = it.status.copy(state = PlayerState.ERROR, error = e.readable())) }
            }
        }
    }

    private var endCheckJob: Job? = null

    /** Routes opened for the current session (closed when it ends). */
    private val routes = java.util.concurrent.CopyOnWriteArrayList<MediaRoute>()

    /** [entries] with URLs [device] can reach; a TV on another router may need a tunnel / port. */
    private suspend fun routed(device: CastDevice, entries: List<QueueEntry>, ip: String, port: Int): List<QueueEntry> {
        val route = MediaRoutes.routeFor(device, ip, port)
        routes += route
        when (route.kind) {
            MediaRoute.Kind.TUNNEL, MediaRoute.Kind.PORT_MAPPING -> Diag.log("cast", "${device.name}: on another router, using ${route.kind}")
            MediaRoute.Kind.DIRECT_ACROSS_ROUTERS -> emit(
                if (device.protocol == CastProtocol.CASTLITE) {
                    "${device.name} is on another router. If it doesn't play, update the CastLite TV app."
                } else {
                    "${device.name} is on another router. If it doesn't play, turn on UPnP in your routers or join the TV's Wi-Fi."
                },
            )
            MediaRoute.Kind.DIRECT -> Unit
        }
        return entries.map { e -> e.copy(url = route.map(e.url), subtitleUrl = e.subtitleUrl?.let(route::map)) }
    }

    /** A finished session gets no more status changes, so re-check it after the grace period. */
    private fun scheduleEndCheck(c: CastController, policy: SessionEndPolicy) {
        if (endCheckJob?.isActive == true) return
        endCheckJob = scope.launch {
            delay(21_000)
            if (controller === c && c.status.value.state == PlayerState.ENDED &&
                policy.onStatus(PlayerState.ENDED, System.currentTimeMillis()) == SessionEndPolicy.Verdict.END_NOW
            ) {
                Diag.log("cast", "Queue finished — ending the session")
                endSession(stopTv = false)
            }
        }
    }

    /** If the TV is off and we know its MAC address, switch it on and try once more. */
    private suspend fun loadWithWake(c: CastController, entries: List<QueueEntry>, index: Int, positionMs: Long, options: CastOptions) {
        try {
            c.load(entries, index, positionMs, options)
        } catch (e: CastException) {
            val mac = _macs.value[c.device.id]
            if (mac == null || !e.isUnreachable()) throw e
            emit("${c.device.name} seems to be off — turning it on…")
            withContext(Dispatchers.IO) { WakeOnLan.send(mac, LanMonitor(app).current().ipAddress) }
            Diag.log("wol", "Auto-wake ${c.device.name}, retrying in ${WAKE_WAIT_MS / 1000}s")
            delay(WAKE_WAIT_MS)
            c.load(entries, index, positionMs, options)
        }
    }

    private fun Throwable.isUnreachable(): Boolean = generateSequence(this) { it.cause }.any {
        it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException
    }

    /** Re-sends the current item at its last position (after Wi-Fi came back, an error, ...). */
    fun retry() {
        val s = _session.value ?: return cast()
        cast(s.status.index, s.status.positionMs)
    }

    fun togglePlayPause() {
        val state = _session.value?.status?.state ?: return
        when (state) {
            PlayerState.PLAYING, PlayerState.LOADING -> command(allTvs = true) { pause() }
            PlayerState.PAUSED -> command(allTvs = true) { play() }
            PlayerState.ENDED, PlayerState.IDLE -> cast(0)
            PlayerState.ERROR -> retry()
            PlayerState.CONNECTING -> Unit
        }
    }

    /** Explicit play/pause for the lock screen, headset buttons and notification. */
    fun play() {
        when (_session.value?.status?.state ?: return) {
            PlayerState.PAUSED -> command(allTvs = true) { play() }
            PlayerState.ENDED, PlayerState.IDLE -> cast(0)
            PlayerState.ERROR -> retry()
            else -> Unit
        }
    }

    fun pause() {
        val state = _session.value?.status?.state ?: return
        if (state == PlayerState.PLAYING || state == PlayerState.LOADING) command(allTvs = true) { pause() }
    }

    fun seekTo(ms: Long) {
        Diag.log("cast", "Seek to ${ms} ms (TV reported ${_session.value?.status?.positionMs} / ${_session.value?.status?.durationMs} ms)")
        command(allTvs = true) { seekTo(ms) }
    }
    fun next() = command(allTvs = true) { next() }
    fun previous() = command(allTvs = true) { previous() }
    fun jumpTo(index: Int) = command(allTvs = true) { jumpTo(index) }

    /** Volume applies to the main TV only (each room has its own level). */
    fun setVolume(percent: Int) = command { setVolume(percent) }

    /** Stops playback on the TV(s) and ends the session. */
    fun stop() = endSession(stopTv = true)

    private fun endSession(stopTv: Boolean) {
        val all = listOfNotNull(controller) + extraControllers
        releaseController()
        _session.value = null
        if (stopTv) all.forEach { c -> scope.launch { runCatching { c.stop() } } }
    }

    private fun releaseController() {
        endCheckJob?.cancel()
        endCheckJob = null
        statusJob?.cancel()
        statusJob = null
        controller?.release()
        controller = null
        extraControllers.forEach { it.release() }
        extraControllers = emptyList()
        routes.forEach { it.release() }
        routes.clear()
    }

    /** Runs [block] on the main TV (and, with [allTvs], on the extra ones too). */
    private fun command(allTvs: Boolean = false, block: suspend CastController.() -> Unit) {
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
        if (allTvs) extraControllers.forEach { ec ->
            scope.launch {
                try {
                    ec.block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diag.log("cast", "${ec.device.name}: ${e.readable()}")
                }
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

    /**
     * Wi-Fi drop → the TV can't fetch media any more; say so instead of failing silently.
     * When Wi-Fi comes back (often while the phone is locked, so nobody can tap Retry),
     * resume automatically if the TV lost the stream or our address changed.
     */
    private fun watchWifi() {
        scope.launch {
            var previous: LanStatus? = null
            LanMonitor(app).status.collect { lan ->
                Diag.log("wifi", "${lan.kind} ${lan.ipAddress ?: "-"}")
                val s = _session.value
                _session.update {
                    it?.copy(networkProblem = if (lan.isConnected) null else "Wi-Fi disconnected. Casting resumes automatically when it's back.")
                }
                if (AutoResume.shouldCheck(previous, lan) && s != null) {
                    val addressChanged = AutoResume.addressChanged(previous, lan)
                    scope.launch {
                        // Give the TV a moment: if it simply kept playing, polling shows that.
                        delay(AUTO_RESUME_DELAY_MS)
                        val now = _session.value ?: return@launch
                        if (now.device.id == s.device.id && AutoResume.shouldResume(addressChanged, now.status.state)) {
                            Log.i(TAG, "Wi-Fi back (address changed: $addressChanged) — resuming")
                            emit("Wi-Fi is back — resuming on ${now.device.name}")
                            retry()
                        }
                    }
                }
                previous = lan
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
