package com.castlite.phone.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the home screen renders. */
data class HomeUiState(
    val lan: LanStatus = LanStatus.Disconnected,
    val server: ServerState = ServerState.Stopped,
    val media: List<CastMedia> = emptyList(),
    val requests: List<RequestLogEntry> = emptyList(),
    /** One-off problem to show in a snackbar (e.g. a file that couldn't be read). */
    val message: String? = null,
) {
    /** Full URL of [item] as a TV would fetch it, or null if it isn't reachable yet. */
    fun urlFor(item: CastMedia): String? {
        val port = (server as? ServerState.Running)?.port ?: return null
        val ip = lan.ipAddress ?: return null
        return "http://$ip:$port${item.path}"
    }

    val baseUrl: String?
        get() {
            val port = (server as? ServerState.Running)?.port ?: return null
            val ip = lan.ipAddress ?: return null
            return "http://$ip:$port/"
        }
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val resolver = MediaResolver(app)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<HomeUiState> = combine(
        LanMonitor(app).status,
        ServerMonitor.state,
        MediaLibrary.items,
        ServerMonitor.requests,
        message,
    ) { lan, server, media, requests, msg ->
        HomeUiState(lan, server, media, requests, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Called with the URIs returned by the system picker. */
    fun onMediaPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            // Provider queries can hit disk or the network (cloud files): keep them off the main thread.
            val resolved = withContext(Dispatchers.IO) { uris.map { it to resolver.resolve(it) } }
            MediaLibrary.add(resolved.mapNotNull { it.second })
            val failed = resolved.count { it.second == null }
            if (failed > 0) message.value = "$failed file(s) couldn't be opened and were skipped."
            // Picking media implies the user wants to share it: start serving right away.
            startServer()
        }
    }

    fun remove(item: CastMedia) = MediaLibrary.remove(item.id)

    fun startServer() = MediaServerService.start(getApplication())

    fun stopServer() = MediaServerService.stop(getApplication())

    fun messageShown() {
        message.value = null
    }
}
