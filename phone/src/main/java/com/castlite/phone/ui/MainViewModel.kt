package com.castlite.phone.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.castlite.phone.CastLiteApp
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastManager
import com.castlite.phone.cast.CastSession
import com.castlite.phone.cast.DeviceRepository
import com.castlite.phone.cast.receiver.ReceiverDiscovery
import com.castlite.protocol.TvQrCode
import com.castlite.phone.data.AppPrefs
import com.castlite.phone.data.PowerChecks
import com.castlite.phone.data.PowerStatus
import com.castlite.phone.diagnostics.DiagnosticsReport
import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.Compatibility
import com.castlite.phone.media.MediaLibrary
import com.castlite.phone.media.MediaResolver
import com.castlite.phone.media.TvSupport
import com.castlite.phone.mirror.MirrorManager
import com.castlite.phone.mirror.MirrorUiState
import com.castlite.phone.net.LanMonitor
import com.castlite.phone.net.LanStatus
import com.castlite.phone.server.MediaServerService
import com.castlite.phone.server.RequestLogEntry
import com.castlite.phone.server.ServerMonitor
import com.castlite.phone.server.ServerState
import com.castlite.protocol.RepeatMode
import com.castlite.protocol.SlideshowOptions
import com.castlite.updater.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Resume from 12:34?" */
data class ResumePrompt(val index: Int, val positionMs: Long, val title: String)

/** Everything the home, player and server screens render. */
data class MainUiState(
    val lan: LanStatus = LanStatus.Disconnected,
    val server: ServerState = ServerState.Stopped,
    val media: List<CastMedia> = emptyList(),
    val requests: List<RequestLogEntry> = emptyList(),
    val selectedDevice: CastDevice? = null,
    val session: CastSession? = null,
    val slideshow: SlideshowOptions = SlideshowOptions(),
    val power: PowerStatus = PowerStatus(),
    val batteryPromptDismissed: Boolean = false,
    val update: UpdateState = UpdateState.Idle,
    val appVersion: String = "",
    val repeat: RepeatMode = RepeatMode.OFF,
    /** What the selected TV can play (null = not known yet). */
    val support: TvSupport? = null,
    val extraDevices: List<CastDevice> = emptyList(),
    val macs: Map<String, String> = emptyMap(),
    val resumePrompt: ResumePrompt? = null,
    val mirror: MirrorUiState = MirrorUiState.Idle,
    /** e.g. "Adding 34 files…" while a folder is being read. */
    val busy: String? = null,
) {
    val slideDurationMs: Long get() = slideshow.durationMs

    /** Show the "keep casting when the phone locks" card? */
    val showBackgroundPrompt: Boolean
        get() = power.backgroundRestricted || (!power.unrestrictedBattery && !batteryPromptDismissed)

    val baseUrl: String?
        get() {
            val port = (server as? ServerState.Running)?.port ?: return null
            val ip = lan.ipAddress ?: return null
            return "http://$ip:$port/"
        }

    fun urlFor(item: CastMedia): String? = if (item.isRemote) item.contentUri else baseUrl?.let { it.removeSuffix("/") + item.path }

    fun compatibility(item: CastMedia): Compatibility = Compatibility.check(item, support)

    val selectedMac: String? get() = selectedDevice?.let { macs[it.id] }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val resolver = MediaResolver(app)
    private val prefs = AppPrefs(app)
    private val power = MutableStateFlow(PowerChecks.read(app))
    private val batteryPromptDismissed = MutableStateFlow(prefs.batteryPromptDismissed)
    private val updater = (app as CastLiteApp).updater
    private val localMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val resumePrompt = MutableStateFlow<ResumePrompt?>(null)
    private val busy = MutableStateFlow<String?>(null)

    /** Snackbar messages from this screen and from the casting session. */
    val messages: SharedFlow<String> = merge(localMessages, CastManager.messages, MirrorManager.messages)
        .shareIn(viewModelScope, SharingStarted.Eagerly)

    private data class Net(val lan: LanStatus, val server: ServerState, val requests: List<RequestLogEntry>)
    private data class Cast(val device: CastDevice?, val extras: List<CastDevice>, val session: CastSession?, val support: Map<String, TvSupport>, val macs: Map<String, String>)
    private data class Settings(val slideshow: SlideshowOptions, val repeat: RepeatMode, val power: PowerStatus, val dismissed: Boolean, val update: UpdateState)
    private data class Transient(val resume: ResumePrompt?, val mirror: MirrorUiState, val busy: String?)

    val state: StateFlow<MainUiState> = combine(
        combine(LanMonitor(app).status, ServerMonitor.state, ServerMonitor.requests, ::Net),
        MediaLibrary.items,
        combine(CastManager.selectedDevice, CastManager.extraDevices, CastManager.session, CastManager.support, CastManager.macs, ::Cast),
        combine(CastManager.slideshow, CastManager.repeat, power, batteryPromptDismissed, updater.state, ::Settings),
        combine(resumePrompt, MirrorManager.state, busy, ::Transient),
    ) { net, media, cast, settings, transient ->
        MainUiState(
            lan = net.lan, server = net.server, media = media, requests = net.requests,
            selectedDevice = cast.device, session = cast.session,
            slideshow = settings.slideshow, power = settings.power, batteryPromptDismissed = settings.dismissed,
            update = settings.update, appVersion = updater.installedVersionName, repeat = settings.repeat,
            support = cast.device?.let { cast.support[it.id] }, extraDevices = cast.extras, macs = cast.macs,
            resumePrompt = transient.resume, mirror = transient.mirror, busy = transient.busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    // ---- Adding media ----------------------------------------------------------------

    /** Called with the URIs returned by the system picker (or shared from another app). */
    fun onMediaPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy.value = if (uris.size > 3) "Adding ${uris.size} files…" else null
            // Provider queries can hit disk or the network (cloud files): keep them off the main thread.
            val resolved = withContext(Dispatchers.IO) { uris.map { resolver.resolve(it) } }
            busy.value = null
            MediaLibrary.add(resolved.filterNotNull())
            val failed = resolved.count { it == null }
            if (failed > 0) localMessages.tryEmit("$failed file(s) couldn't be opened or aren't video, photo or audio.")
            // Picking media means you want to share it: have the server ready.
            MediaServerService.start(getApplication())
        }
    }

    /** Everything playable in a folder (and its sub-folders), with matching subtitles. */
    fun onFolderPicked(tree: Uri?) {
        tree ?: return
        viewModelScope.launch {
            busy.value = "Reading folder…"
            val items = try {
                withContext(Dispatchers.IO) { resolver.resolveFolder(tree) }
            } catch (e: Exception) {
                localMessages.tryEmit("Couldn't read that folder: ${e.message}")
                emptyList()
            } finally {
                busy.value = null
            }
            if (items.isEmpty()) {
                localMessages.tryEmit("No videos, photos or music in that folder.")
                return@launch
            }
            MediaLibrary.add(items)
            val subs = items.count { it.subtitle != null }
            localMessages.tryEmit("Added ${items.size} item(s)" + if (subs > 0) " · $subs with subtitles" else "")
            MediaServerService.start(getApplication())
        }
    }

    /** A direct video/audio/photo link or .m3u8 stream; the TV streams it from the internet. */
    fun addLink(url: String) {
        viewModelScope.launch {
            busy.value = "Checking link…"
            try {
                val media = withContext(Dispatchers.IO) { resolver.resolveLink(url) }
                MediaLibrary.add(listOf(media))
                localMessages.tryEmit("Added ${media.displayName}")
            } catch (e: Exception) {
                localMessages.tryEmit(e.message ?: "That link doesn't work")
            } finally {
                busy.value = null
            }
        }
    }

    fun onSubtitlePicked(item: CastMedia, uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            val sub = withContext(Dispatchers.IO) { resolver.resolveSubtitle(uri) }
            if (sub == null) {
                localMessages.tryEmit("Pick a .srt or .vtt subtitle file")
            } else {
                MediaLibrary.setSubtitle(item.id, sub)
                localMessages.tryEmit("Subtitles added to ${item.displayName}")
            }
        }
    }

    fun removeSubtitle(item: CastMedia) = MediaLibrary.setSubtitle(item.id, null)

    /** "Share → CastLite" and "Open with → CastLite" from other apps. */
    fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.streamExtra()?.let(uris::add)
                if (uris.isEmpty()) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val link = text?.let { Regex("https?://\\S+").find(it)?.value }
                    if (link != null) addLink(link) else if (text != null) localMessages.tryEmit("Shared text has no link to cast")
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> uris += intent.streamListExtra()
            Intent.ACTION_VIEW -> intent.data?.let { data ->
                when (data.scheme) {
                    "castlite" -> connectToTv(data.toString())
                    "http", "https" -> addLink(data.toString())
                    else -> uris += data
                }
            }
            else -> return
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { if (it !in uris) uris += it } }
        if (uris.isNotEmpty()) onMediaPicked(uris)
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    /** The TV's QR code scanned with the phone's camera app opens castlite://connect?… */
    private fun connectToTv(link: String) {
        val target = TvQrCode.parse(link) ?: return run { localMessages.tryEmit("That CastLite link isn't valid") }
        viewModelScope.launch {
            busy.value = "Connecting to ${target.name ?: target.host}…"
            try {
                val device = ReceiverDiscovery.probe(target.host, target.port)
                DeviceRepository(getApplication()).addManual(device)
                CastManager.select(device)
                localMessages.tryEmit("Connected to ${device.name}")
            } catch (e: Exception) {
                localMessages.tryEmit("Couldn't reach the TV at ${target.host}. Is CastLite open on it?")
            } finally {
                busy.value = null
            }
        }
    }

    /** Drops queue items whose files were deleted (or lost permission) since last time. */
    fun pruneMissing() {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) { MediaLibrary.items.value.filterNot { resolver.isReadable(it) } }
            if (missing.isNotEmpty()) {
                MediaLibrary.removeAll(missing.map { it.id }.toSet())
                localMessages.tryEmit("Removed ${missing.size} file(s) that are no longer available")
            }
        }
    }

    fun remove(item: CastMedia) = MediaLibrary.remove(item.id)
    fun move(item: CastMedia, delta: Int) = MediaLibrary.move(item.id, delta)
    fun clearQueue() = MediaLibrary.clear()
    fun shuffle() {
        MediaLibrary.shuffle()
        localMessages.tryEmit("Queue shuffled")
    }

    // ---- Casting ------------------------------------------------------------------------

    /**
     * Cast from [index]; asks "Resume?" first if that file was stopped part-way.
     * Returns true if casting started right away (false = waiting for the answer).
     */
    fun requestCast(index: Int): Boolean {
        val item = MediaLibrary.items.value.getOrNull(index) ?: return false
        val saved = CastManager.resumePositionFor(item)
        return if (saved > 0) {
            resumePrompt.value = ResumePrompt(index, saved, item.displayName)
            false
        } else {
            CastManager.cast(index)
            true
        }
    }

    fun answerResume(resume: Boolean) {
        val p = resumePrompt.value ?: return
        resumePrompt.value = null
        CastManager.cast(p.index, if (resume) p.positionMs else 0)
    }

    fun dismissResume() {
        resumePrompt.value = null
    }

    fun togglePlayPause() = CastManager.togglePlayPause()
    fun seekTo(ms: Long) = CastManager.seekTo(ms)
    fun next() = CastManager.next()
    fun previous() = CastManager.previous()
    fun jumpTo(index: Int) = CastManager.jumpTo(index)
    fun setVolume(percent: Int) = CastManager.setVolume(percent)
    fun stopCasting() = CastManager.stop()
    fun retry() = CastManager.retry()
    fun setSlideshow(options: SlideshowOptions) = CastManager.setSlideshow(options)
    fun cycleRepeat() = CastManager.cycleRepeat()
    fun setSubtitles(enabled: Boolean) = CastManager.setSubtitles(enabled)
    fun wakeSelected() = state.value.selectedDevice?.let { CastManager.wake(it) }

    // ---- Mirroring -----------------------------------------------------------------------

    /** Mirroring needs the CastLite TV app; returns an error message if it can't start. */
    fun mirrorProblem(): String? {
        val device = state.value.selectedDevice ?: return "Choose a TV first"
        return if (device.protocol != com.castlite.phone.cast.CastProtocol.CASTLITE) {
            "Screen mirroring works with the CastLite TV app. For LG TVs, use your phone's own Screen Cast / Smart View if it has one."
        } else {
            null
        }
    }

    fun startMirroring(resultCode: Int, data: Intent, withAudio: Boolean) {
        val device = state.value.selectedDevice ?: return
        CastManager.stop()
        MirrorManager.start(getApplication(), resultCode, data, device, withAudio)
    }

    fun stopMirroring() = MirrorManager.stop(getApplication())

    // ---- Server, power, updates, diagnostics ----------------------------------------------

    fun startServer() = MediaServerService.start(getApplication())
    fun stopServer() {
        CastManager.stop()
        MediaServerService.stop(getApplication())
    }

    /** Re-read battery settings (call on resume: the user may have just changed them). */
    fun refreshPower() {
        power.value = PowerChecks.read(getApplication())
    }

    fun dismissBackgroundPrompt() {
        prefs.batteryPromptDismissed = true
        batteryPromptDismissed.value = true
    }

    fun checkForUpdates() = updater.check(userInitiated = true)

    /** Installs a downloaded update now (even while casting — the user asked for it). */
    fun installUpdate() = updater.installIfReady(userInitiated = true)

    fun updatePermissionIntent() = updater.permissionSettingsIntent()

    /** Builds the diagnostics report and returns a share intent for it. */
    fun diagnostics(onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            busy.value = "Collecting diagnostics…"
            try {
                val intent = withContext(Dispatchers.IO) { DiagnosticsReport.create(getApplication(), state.value) }
                onReady(intent)
            } catch (e: Exception) {
                localMessages.tryEmit("Couldn't create the report: ${e.message}")
            } finally {
                busy.value = null
            }
        }
    }

    fun showMessage(text: String) {
        localMessages.tryEmit(text)
    }
}
