package com.castlite.tv

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.castlite.protocol.Command
import com.castlite.protocol.MediaType
import com.castlite.protocol.MirrorProtocol
import com.castlite.protocol.ReceiverApiServer
import com.castlite.protocol.ReceiverHandler
import com.castlite.protocol.ReceiverInfo
import com.castlite.protocol.ReceiverProtocol
import com.castlite.protocol.ReceiverStatus
import com.castlite.tv.mirror.MirrorReceiver
import com.castlite.tv.net.NsdAdvertiser
import com.castlite.tv.playback.PlaybackEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
 * The always-on part of the TV app (process singleton, started by [ReceiverService]):
 * the JSON API, the mDNS advertisement, the player and the mirror receiver. It keeps
 * listening while the TV shows other apps, and brings CastLite to the front when the
 * phone starts casting.
 */
object ReceiverRuntime : ReceiverHandler {

    private lateinit var app: Application
    private val scope = MainScope()

    lateinit var engine: PlaybackEngine
        private set
    lateinit var mirror: MirrorReceiver
        private set

    private val nsd by lazy { NsdAdvertiser(app) }
    private var server: ReceiverApiServer? = null
    private lateinit var deviceName: String

    private val _info = MutableStateFlow(ReceiverInfoState(""))
    val info: StateFlow<ReceiverInfoState> = _info.asStateFlow()

    /** True while MainActivity is on screen. */
    val activityVisible = MutableStateFlow(false)

    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application
        deviceName = Settings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
        _info.value = ReceiverInfoState(deviceName, LocalAddress.ipv4())
        engine = PlaybackEngine(app, scope)
        mirror = MirrorReceiver(
            onConnect = { phone ->
                // Stop any playback and make sure the mirror screen can be seen.
                runBlocking(Dispatchers.Main.immediate) { engine.stop(null) }
                ensureVisible("mirror from $phone")
            },
            onDisconnect = { },
        )
        TvLog.log("app", "CastLite TV ${appVersion()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    /** Starts listening (idempotent). Called by [ReceiverService]. */
    fun start() {
        refreshAddress()
        if (server == null) {
            val s = ReceiverApiServer(this, ReceiverProtocol.PORT)
            try {
                s.start(5_000, true)
                server = s
                _info.value = _info.value.copy(listening = true, serverError = null)
                TvLog.log("app", "Listening on port ${ReceiverProtocol.PORT}")
            } catch (e: IOException) {
                TvLog.log("app", "Couldn't open port ${ReceiverProtocol.PORT}: ${e.message}")
                _info.value = _info.value.copy(
                    listening = false,
                    serverError = "Port ${ReceiverProtocol.PORT} is busy. Restart the TV and open CastLite again.",
                )
                return
            }
        }
        mirror.start()
        nsd.register(deviceName, ReceiverProtocol.PORT)
    }

    fun stop() {
        nsd.unregister()
        mirror.stop()
        server?.stop()
        server = null
        _info.value = _info.value.copy(listening = false)
        scope.launch { engine.stop(null) }
    }

    fun refreshAddress() {
        _info.value = _info.value.copy(ipAddress = LocalAddress.ipv4())
    }

    /** The activity left the screen: stop video/photos/mirroring, keep music going. */
    fun onActivityHidden() {
        activityVisible.value = false
        engine.onScreenHidden()
        mirror.disconnect()
    }

    // ---- ReceiverHandler (called on server threads) --------------------------------

    override fun info() = ReceiverInfo(
        name = deviceName,
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        appVersion = appVersion(),
        capabilities = DecoderCapabilities.current,
        mirrorPort = MirrorProtocol.PORT,
    )

    override fun status(): ReceiverStatus = engine.status().copy(mirroring = mirror.state.value.active)

    override fun handle(command: Command, client: String): ReceiverStatus {
        val needsScreen = command is Command.Load && command.items.any { it.type != MediaType.AUDIO }
        if (needsScreen && !activityVisible.value) bringToFront()
        // ExoPlayer may only be touched on the main thread.
        val status = runBlocking(Dispatchers.Main.immediate) {
            engine.execute(command, client)
            status()
        }
        if (needsScreen && !ensureVisible("cast from $client")) {
            runBlocking(Dispatchers.Main.immediate) { engine.stop("CastLite couldn't open itself on the TV") }
            throw IllegalStateException(
                "The CastLite app on the TV is closed and Android didn't let it open itself. " +
                    "Open CastLite on the TV once (or allow it to \"display over other apps\") and try again.",
            )
        }
        return status
    }

    override fun diagnostics(): String = buildString {
        appendLine("CastLite TV ${appVersion()}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Name: $deviceName, IP: ${_info.value.ipAddress}, listening: ${_info.value.listening}")
        appendLine("App on screen: ${activityVisible.value}, can draw overlays: ${canDrawOverlays()}")
        appendLine("Decoders: ${DecoderCapabilities.current}")
        appendLine("Status: ${status()}")
        appendLine("Mirror: ${mirror.state.value}")
        appendLine()
        appendLine("--- TV log ---")
        append(TvLog.dump())
    }

    // ---- Bringing the app to the front ------------------------------------------------

    /** Opens the receiver screen if needed and waits for it; false if Android blocked it. */
    private fun ensureVisible(reason: String): Boolean {
        if (activityVisible.value) return true
        bringToFront()
        val shown = runBlocking { withTimeoutOrNull(4_000) { activityVisible.first { it } } } != null
        TvLog.log("app", if (shown) "Opened the app for $reason" else "Android blocked opening the app for $reason")
        return shown
    }

    private fun bringToFront() {
        try {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: Exception) {
            TvLog.log("app", "Couldn't start activity: ${e.message}")
        }
    }

    private fun canDrawOverlays() = Settings.canDrawOverlays(app)

    private fun appVersion(): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
