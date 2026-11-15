package com.castlite.phone.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Lifecycle of the local media server, observed by the UI and the notification. */
sealed interface ServerState {
    data object Stopped : ServerState
    data class Running(val port: Int) : ServerState
    data class Failed(val message: String) : ServerState
}

/** One served HTTP request; shown in the UI so you can see what the TV asked for. */
data class RequestLogEntry(
    val timeMillis: Long,
    val client: String,
    val method: String,
    val path: String,
    val range: String?,
    val status: Int,
    val userAgent: String?,
)

/** Shared, observable status of the server (written by the service, read by the UI). */
object ServerMonitor {
    private const val MAX_LOG = 30

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _requests = MutableStateFlow<List<RequestLogEntry>>(emptyList())
    val requests: StateFlow<List<RequestLogEntry>> = _requests.asStateFlow()

    fun setState(state: ServerState) {
        _state.value = state
    }

    fun record(entry: RequestLogEntry) {
        _requests.update { (listOf(entry) + it).take(MAX_LOG) }
        com.castlite.phone.diagnostics.Diag.log(
            "http", "${entry.client} ${entry.method} ${entry.path} ${entry.range ?: ""} → ${entry.status} (${entry.userAgent ?: "?"})",
        )
    }
}
