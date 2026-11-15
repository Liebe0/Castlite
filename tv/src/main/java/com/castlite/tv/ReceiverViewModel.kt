package com.castlite.tv

import android.app.Application
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.castlite.protocol.Command
import com.castlite.protocol.ReceiverApiServer
import com.castlite.protocol.ReceiverHandler
import com.castlite.protocol.ReceiverInfo
import com.castlite.protocol.ReceiverProtocol
import com.castlite.protocol.ReceiverStatus
import com.castlite.tv.net.NsdAdvertiser
import com.castlite.tv.playback.PlaybackEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Address and health of the receiver, shown on the idle screen. */
data class ReceiverInfoState(
    val deviceName: String,
    val ipAddress: String? = null,
    val port: Int = ReceiverProtocol.PORT,
    val listening: Boolean = false,
    val serverError: String? = null,
)

/**
 * Hosts the receiver: the JSON API server (phone → TV commands), the NSD
 * advertisement and the [PlaybackEngine]. Receiving is active while the activity
 * is started; leaving the app stops playback and the server.
 */
class ReceiverViewModel(app: Application) : AndroidViewModel(app), ReceiverHandler {

    val engine = PlaybackEngine(app, viewModelScope)
    private val nsd = NsdAdvertiser(app)
    private var server: ReceiverApiServer? = null

    private val deviceName: String = Settings.Global.getString(app.contentResolver, "device_name")
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private val _info = MutableStateFlow(ReceiverInfoState(deviceName, LocalAddress.ipv4()))
    val info: StateFlow<ReceiverInfoState> = _info.asStateFlow()

    fun startReceiving() {
        refreshAddress()
        if (server == null) {
            val s = ReceiverApiServer(this, ReceiverProtocol.PORT)
            try {
                s.start(5_000, true)
                server = s
                _info.value = _info.value.copy(listening = true, serverError = null)
            } catch (e: IOException) {
                Log.e(TAG, "Couldn't open port ${ReceiverProtocol.PORT}", e)
                _info.value = _info.value.copy(
                    listening = false,
                    serverError = "Port ${ReceiverProtocol.PORT} is busy. Restart the TV and open CastLite again.",
                )
                return
            }
        }
        nsd.register(deviceName, ReceiverProtocol.PORT)
    }

    fun stopReceiving() {
        nsd.unregister()
        server?.stop()
        server = null
        _info.value = _info.value.copy(listening = false)
        engine.stop(message = null)
    }

    fun refreshAddress() {
        _info.value = _info.value.copy(ipAddress = LocalAddress.ipv4())
    }

    fun handleKey(event: KeyEvent): Boolean = engine.handleKey(event)

    // ---- ReceiverHandler (called on server threads) --------------------------------

    override fun info() = ReceiverInfo(name = deviceName, model = "${Build.MANUFACTURER} ${Build.MODEL}", appVersion = appVersion())

    override fun status(): ReceiverStatus = engine.status()

    override fun handle(command: Command, client: String): ReceiverStatus =
        // ExoPlayer may only be touched on the main thread.
        runBlocking(Dispatchers.Main.immediate) {
            engine.execute(command, client)
            engine.status()
        }

    private fun appVersion(): String = try {
        getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    override fun onCleared() {
        stopReceiving()
        engine.release()
    }

    private companion object {
        const val TAG = "ReceiverViewModel"
    }
}
