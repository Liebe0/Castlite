package com.castlite.phone.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastManager
import com.castlite.phone.CastLiteApp
import com.castlite.phone.cast.CastSession
import com.castlite.updater.UpdateState
import com.castlite.phone.data.AppPrefs
import com.castlite.phone.data.PowerChecks
import com.castlite.phone.data.PowerStatus
import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.MediaLibrary
import com.castlite.phone.media.MediaResolver
import com.castlite.phone.net.LanMonitor
import com.castlite.phone.net.LanStatus
import com.castlite.phone.server.MediaServerService
import com.castlite.phone.server.RequestLogEntry
import com.castlite.phone.server.ServerMonitor
import com.castlite.phone.server.ServerState
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

/** Everything the home, player and server screens render. */
data class MainUiState(
    val lan: LanStatus = LanStatus.Disconnected,
    val server: ServerState = ServerState.Stopped,
    val media: List<CastMedia> = emptyList(),
    val requests: List<RequestLogEntry> = emptyList(),
    val selectedDevice: CastDevice? = null,
    val session: CastSession? = null,
    val slideDurationMs: Long = 5_000,
    val power: PowerStatus = PowerStatus(),
    val batteryPromptDismissed: Boolean = false,
    val update: UpdateState = UpdateState.Idle,
    val appVersion: String = "",
) {
    /** Show the "keep casting when the phone locks" card? */
    val showBackgroundPrompt: Boolean
        get() = power.backgroundRestricted || (!power.unrestrictedBattery && !batteryPromptDismissed)

    val baseUrl: String?
        get() {
            val port = (server as? ServerState.Running)?.port ?: return null
            val ip = lan.ipAddress ?: return null
            return "http://$ip:$port/"
        }

    fun urlFor(item: CastMedia): String? = baseUrl?.let { it.removeSuffix("/") + item.path }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val resolver = MediaResolver(app)
    private val prefs = AppPrefs(app)
    private val power = MutableStateFlow(PowerChecks.read(app))
    private val batteryPromptDismissed = MutableStateFlow(prefs.batteryPromptDismissed)
    private val updater = (app as CastLiteApp).updater
    private val localMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Snackbar messages from this screen and from the casting session. */
    val messages: SharedFlow<String> = merge(localMessages, CastManager.messages)
        .shareIn(viewModelScope, SharingStarted.Eagerly)

    val state: StateFlow<MainUiState> = combine(
        combine(LanMonitor(app).status, ServerMonitor.state, ServerMonitor.requests, ::Triple),
        MediaLibrary.items,
        CastManager.selectedDevice,
        CastManager.session,
        combine(CastManager.slideDurationMs, power, batteryPromptDismissed, updater.state) { slide, pwr, dismissed, upd ->
            Settings(slide, pwr, dismissed, upd)
        },
    ) { (lan, server, requests), media, device, session, settings ->
        MainUiState(
            lan, server, media, requests, device, session,
            settings.slideDurationMs, settings.power, settings.batteryPromptDismissed, settings.update,
            appVersion = updater.installedVersionName,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private data class Settings(val slideDurationMs: Long, val power: PowerStatus, val batteryPromptDismissed: Boolean, val update: UpdateState)

    /** Called with the URIs returned by the system picker. */
    fun onMediaPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            // Provider queries can hit disk or the network (cloud files): keep them off the main thread.
            val resolved = withContext(Dispatchers.IO) { uris.map { resolver.resolve(it) } }
            MediaLibrary.add(resolved.filterNotNull())
            val failed = resolved.count { it == null }
            if (failed > 0) localMessages.tryEmit("$failed file(s) couldn't be opened or aren't video, photo or audio.")
            // Picking media means you want to share it: have the server ready.
            MediaServerService.start(getApplication())
        }
    }

    fun remove(item: CastMedia) = MediaLibrary.remove(item.id)
    fun move(item: CastMedia, delta: Int) = MediaLibrary.move(item.id, delta)
    fun clearQueue() = MediaLibrary.clear()

    fun cast(startIndex: Int = 0) = CastManager.cast(startIndex)
    fun togglePlayPause() = CastManager.togglePlayPause()
    fun seekTo(ms: Long) = CastManager.seekTo(ms)
    fun next() = CastManager.next()
    fun previous() = CastManager.previous()
    fun jumpTo(index: Int) = CastManager.jumpTo(index)
    fun setVolume(percent: Int) = CastManager.setVolume(percent)
    fun stopCasting() = CastManager.stop()
    fun retry() = CastManager.retry()
    fun setSlideDuration(ms: Long) = CastManager.setSlideDuration(ms)

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

    fun showMessage(text: String) {
        localMessages.tryEmit(text)
    }
}
