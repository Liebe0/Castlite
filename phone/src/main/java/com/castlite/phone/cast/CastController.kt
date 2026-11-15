package com.castlite.phone.cast

import com.castlite.phone.cast.dlna.DlnaController
import com.castlite.phone.cast.googlecast.GoogleCastSupport
import com.castlite.phone.cast.receiver.ReceiverCastController
import com.castlite.protocol.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Controls playback on one TV, whatever protocol it speaks. The UI and [CastManager]
 * only use this interface, so adding a protocol (e.g. Google Cast) means adding one
 * implementation plus a discovery source — nothing else changes.
 *
 * All methods may throw [CastException] with a user-readable message.
 * Implementations keep [status] fresh by polling the TV.
 */
interface CastController {
    val device: CastDevice
    val status: StateFlow<PlaybackStatus>

    /** Sends the whole queue and starts playing [startIndex] (at [startPositionMs]). */
    suspend fun load(queue: List<QueueEntry>, startIndex: Int, startPositionMs: Long, options: CastOptions)

    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun setVolume(percent: Int)
    suspend fun next()
    suspend fun previous()
    suspend fun jumpTo(index: Int)
    suspend fun setRepeat(mode: RepeatMode)

    /** Show/hide subtitles; throws [CastException] if the TV can't switch them. */
    suspend fun setSubtitles(enabled: Boolean)

    /** Stops polling and frees resources. Does not stop the TV. */
    fun release()
}

/** Creates the right [CastController] for a device's protocol. */
object CastControllerFactory {
    fun create(device: CastDevice, scope: CoroutineScope): CastController = when (device.protocol) {
        CastProtocol.DLNA -> DlnaController(device, scope)
        CastProtocol.CASTLITE -> ReceiverCastController(device, scope)
        CastProtocol.GOOGLE_CAST -> GoogleCastSupport.createController(device, scope)
    }
}
