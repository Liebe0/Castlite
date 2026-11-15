package com.castlite.phone.cast.receiver

import com.castlite.phone.cast.CastController
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastException
import com.castlite.phone.cast.PlaybackStatus
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.cast.QueueEntry
import com.castlite.phone.media.MediaKind
import com.castlite.protocol.Command
import com.castlite.protocol.MediaType
import com.castlite.protocol.QueueItem
import com.castlite.protocol.ReceiverClient
import com.castlite.protocol.ReceiverException
import com.castlite.protocol.ReceiverState
import com.castlite.protocol.ReceiverStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Controls the CastLite TV app over its HTTP/JSON API (see `protocol` module).
 * Unlike DLNA, the TV app holds the whole queue and runs the slideshow itself, so
 * this class mostly forwards commands and polls `/api/status`.
 */
class ReceiverCastController(
    override val device: CastDevice,
    private val scope: CoroutineScope,
    private val client: ReceiverClient = ReceiverClient(device.host, device.receiverPort),
    private val pollIntervalMs: Long = 1_000,
) : CastController {

    private val _status = MutableStateFlow(PlaybackStatus(PlayerState.IDLE))
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()
    private var pollJob: Job? = null
    private var failures = 0

    override suspend fun load(queue: List<QueueEntry>, startIndex: Int, startPositionMs: Long, slideDurationMs: Long) {
        val items = queue.map { e ->
            QueueItem(
                url = e.url,
                title = e.media.displayName,
                mimeType = e.media.mimeType,
                type = when (e.media.kind) {
                    MediaKind.IMAGE -> MediaType.IMAGE
                    MediaKind.AUDIO -> MediaType.AUDIO
                    else -> MediaType.VIDEO
                },
                durationMs = e.media.durationMs,
            )
        }
        send(Command.Load(items, startIndex, startPositionMs, slideDurationMs))
        startPolling()
    }

    override suspend fun play() = send(Command.Play)
    override suspend fun pause() = send(Command.Pause)
    override suspend fun stop() = send(Command.Stop)
    override suspend fun seekTo(positionMs: Long) = send(Command.Seek(positionMs))
    override suspend fun setVolume(percent: Int) = send(Command.SetVolume(percent.coerceIn(0, 100)))
    override suspend fun next() = send(Command.Next)
    override suspend fun previous() = send(Command.Previous)
    override suspend fun jumpTo(index: Int) = send(Command.JumpTo(index))

    override fun release() {
        pollJob?.cancel()
    }

    private suspend fun send(command: Command) {
        val status = io { client.send(command) }
        _status.value = status.toPlayback()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                try {
                    _status.value = io { client.status() }.toPlayback()
                    failures = 0
                } catch (e: CastException) {
                    if (++failures >= 3) {
                        _status.value = _status.value.copy(
                            state = PlayerState.ERROR,
                            error = "Lost connection to ${device.name}. Is the CastLite TV app still open?",
                        )
                    }
                }
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: ReceiverException) {
            throw CastException(e.message ?: "The TV refused the command", e)
        } catch (e: ConnectException) {
            throw CastException("Can't reach ${device.name}. Open the CastLite app on the TV.", e)
        } catch (e: SocketTimeoutException) {
            throw CastException("${device.name} isn't responding.", e)
        } catch (e: IOException) {
            throw CastException("Network problem talking to ${device.name}: ${e.message}", e)
        }
    }

    private fun ReceiverStatus.toPlayback() = PlaybackStatus(
        state = when (state) {
            ReceiverState.IDLE -> PlayerState.IDLE
            ReceiverState.LOADING -> PlayerState.LOADING
            ReceiverState.PLAYING -> PlayerState.PLAYING
            ReceiverState.PAUSED -> PlayerState.PAUSED
            ReceiverState.ENDED -> PlayerState.ENDED
            ReceiverState.ERROR -> PlayerState.ERROR
        },
        index = index.coerceAtLeast(0),
        positionMs = positionMs,
        durationMs = durationMs,
        volume = volumePercent,
        error = error,
    )
}
