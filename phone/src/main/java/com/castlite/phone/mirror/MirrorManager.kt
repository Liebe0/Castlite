package com.castlite.phone.mirror

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.castlite.phone.cast.CastDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MirrorUiState {
    data object Idle : MirrorUiState
    data class Starting(val tv: String) : MirrorUiState
    data class Active(val tv: String, val width: Int, val height: Int, val withAudio: Boolean) : MirrorUiState
}

/** App-wide state of screen mirroring; the work happens in [MirrorService]. */
object MirrorManager {
    private val _state = MutableStateFlow<MirrorUiState>(MirrorUiState.Idle)
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** [resultCode]/[data] come from the system "Start recording or casting?" dialog. */
    fun start(context: Context, resultCode: Int, data: Intent, device: CastDevice, withAudio: Boolean) {
        _state.value = MirrorUiState.Starting(device.name)
        val intent = Intent(context, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(MirrorService.EXTRA_DATA, data)
            .putExtra(MirrorService.EXTRA_HOST, device.host)
            .putExtra(MirrorService.EXTRA_NAME, device.name)
            .putExtra(MirrorService.EXTRA_AUDIO, withAudio)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            failed("Couldn't start mirroring: ${e.message}")
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, MirrorService::class.java))
        _state.value = MirrorUiState.Idle
    }

    internal fun active(tv: String, width: Int, height: Int, audio: Boolean) {
        _state.value = MirrorUiState.Active(tv, width, height, audio)
    }

    internal fun stopped(message: String?) {
        _state.value = MirrorUiState.Idle
        message?.let { _messages.tryEmit(it) }
    }

    internal fun failed(message: String) = stopped(message)
}
