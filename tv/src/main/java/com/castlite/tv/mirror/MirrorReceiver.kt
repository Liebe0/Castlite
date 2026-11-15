package com.castlite.tv.mirror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.castlite.protocol.MirrorFrame
import com.castlite.protocol.MirrorProtocol
import com.castlite.protocol.MirrorReader
import com.castlite.tv.TvLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** What the mirror screen shows. */
data class MirrorState(
    val active: Boolean = false,
    val phone: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
    val fps: Int = 0,
)

/**
 * Receives the phone's screen (see [MirrorProtocol]) and plays it with as little delay
 * as possible: H.264 goes straight into a hardware decoder rendering to the screen's
 * Surface (no buffering), PCM audio into an AudioTrack that drops data rather than lag.
 *
 * One phone at a time; a new connection replaces the current one.
 */
class MirrorReceiver(
    /** Called on a background thread when a phone connects; return false to refuse. */
    private val onConnect: (phone: String) -> Boolean,
    private val onDisconnect: () -> Unit,
) {
    private val _state = MutableStateFlow(MirrorState())
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    /** Set by the mirror screen when its SurfaceView is ready (null when it goes away). */
    @Volatile var surface: Surface? = null
        set(value) {
            field = value
            synchronized(codecLock) { pendingSurfaceChange = true }
        }

    private var serverSocket: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private val codecLock = Any()
    private var pendingSurfaceChange = false

    fun start(port: Int = MirrorProtocol.PORT) {
        if (serverSocket != null) return
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            TvLog.log("mirror", "Can't listen on $port: ${e.message}")
            return
        }
        serverSocket = ss
        Thread({
            while (!ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                disconnect() // a new phone takes over
                client = s
                Thread({ session(s) }, "mirror-session").start()
            }
        }, "mirror-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        disconnect()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** Ends the current session (BACK on the remote, or the app closing). */
    fun disconnect() {
        runCatching { client?.close() }
        client = null
    }

    private fun session(socket: Socket) {
        val phone = socket.inetAddress?.hostAddress ?: "?"
        TvLog.log("mirror", "Phone $phone connected")
        socket.tcpNoDelay = true
        var decoder: MediaCodec? = null
        var audioTrack: AudioTrack? = null
        var configFrame: MirrorFrame? = null
        var waitingForKeyframe = true
        var frames = 0
        var fpsWindowStart = System.currentTimeMillis()
        try {
            if (!onConnect(phone)) {
                TvLog.log("mirror", "Refused: the TV app couldn't come to the front")
                return
            }
            _state.value = MirrorState(active = true, phone = phone)
            val input = socket.getInputStream()
            MirrorProtocol.readHeader(input)
            val reader = MirrorReader(input)
            while (true) {
                val frame = reader.next() ?: break
                when (frame.type) {
                    MirrorProtocol.TYPE_VIDEO_CONFIG -> {
                        val (w, h) = frame.configInts()
                        TvLog.log("mirror", "Video ${w}x$h")
                        _state.update { it.copy(width = w, height = h) }
                        decoder?.let { releaseQuietly(it) }
                        decoder = null
                        configFrame = frame
                        waitingForKeyframe = true
                    }
                    MirrorProtocol.TYPE_VIDEO_FRAME -> {
                        val config = configFrame ?: continue
                        // (Re)create the decoder when there's a surface, or move it to a new one.
                        val surfaceChanged = synchronized(codecLock) { pendingSurfaceChange.also { pendingSurfaceChange = false } }
                        val target = surface
                        if (target == null) {
                            decoder?.let { releaseQuietly(it) }
                            decoder = null
                            waitingForKeyframe = true
                            continue
                        }
                        if (decoder != null && surfaceChanged) {
                            try {
                                decoder.setOutputSurface(target)
                            } catch (e: Exception) {
                                releaseQuietly(decoder)
                                decoder = null
                            }
                        }
                        if (decoder == null) {
                            decoder = createDecoder(config, target) ?: continue
                            waitingForKeyframe = true
                        }
                        if (waitingForKeyframe && !frame.isKeyframe) continue
                        waitingForKeyframe = false
                        feed(decoder, frame)
                        frames++
                        val now = System.currentTimeMillis()
                        if (now - fpsWindowStart >= 1_000) {
                            _state.update { it.copy(fps = frames) }
                            frames = 0
                            fpsWindowStart = now
                        }
                    }
                    MirrorProtocol.TYPE_AUDIO_CONFIG -> {
                        val (rate, channels) = frame.configInts()
                        audioTrack?.release()
                        audioTrack = createAudioTrack(rate, channels)
                        _state.update { it.copy(hasAudio = audioTrack != null) }
                    }
                    MirrorProtocol.TYPE_AUDIO_PCM -> audioTrack?.let { track ->
                        // Non-blocking: if the TV falls behind, drop audio instead of adding delay.
                        track.write(frame.data, 0, frame.data.size, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    MirrorProtocol.TYPE_END -> break
                }
            }
        } catch (e: Exception) {
            TvLog.log("mirror", "Session ended: ${e.message}")
        } finally {
            decoder?.let { releaseQuietly(it) }
            audioTrack?.let { runCatching { it.stop() }; it.release() }
            runCatching { socket.close() }
            if (client === socket) client = null
            if (client == null) {
                _state.value = MirrorState()
                onDisconnect()
            }
            TvLog.log("mirror", "Phone $phone disconnected")
        }
    }

    private fun createDecoder(config: MirrorFrame, surface: Surface): MediaCodec? = try {
        val (w, h) = config.configInts()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        val (sps, pps) = H264.splitParameterSets(config.videoCsd())
        sps?.let { format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it)) }
        pps?.let { format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(it)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, surface, null, 0)
            start()
        }
    } catch (e: Exception) {
        TvLog.log("mirror", "Decoder failed: ${e.message}")
        null
    }

    /** Queue one access unit and render whatever is decoded immediately (no A/V sync = no lag). */
    private fun feed(codec: MediaCodec, frame: MirrorFrame) {
        try {
            val inIndex = codec.dequeueInputBuffer(20_000)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex) ?: return
                buf.clear()
                if (frame.data.size > buf.capacity()) {
                    codec.queueInputBuffer(inIndex, 0, 0, frame.ptsUs, 0)
                    return
                }
                buf.put(frame.data)
                codec.queueInputBuffer(inIndex, 0, frame.data.size, frame.ptsUs, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                if (out >= 0) codec.releaseOutputBuffer(out, true) else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break
            }
        } catch (e: IllegalStateException) {
            TvLog.log("mirror", "Decode error: ${e.message}")
        }
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int): AudioTrack? = try {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minBuffer * 2) // small buffer = low delay
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    } catch (e: Exception) {
        TvLog.log("mirror", "Audio failed: ${e.message}")
        null
    }

    private fun releaseQuietly(codec: MediaCodec) {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/** H.264 helpers (pure, unit-tested). */
object H264 {
    /**
     * Splits an Annex-B blob from the encoder's codec-config output into SPS and PPS
     * (each keeping its start code), which is what MediaFormat's csd-0/csd-1 expect.
     */
    fun splitParameterSets(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        val starts = mutableListOf<Int>() // index of first byte after each start code
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) { starts += i + 3; i += 3; continue }
                if (i + 4 <= data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) { starts += i + 4; i += 4; continue }
            }
            i++
        }
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        starts.forEachIndexed { n, payloadStart ->
            if (payloadStart >= data.size) return@forEachIndexed
            val nalEnd = starts.getOrNull(n + 1)?.let { next -> backToStartCode(data, next) } ?: data.size
            val unit = byteArrayOf(0, 0, 0, 1) + data.copyOfRange(payloadStart, nalEnd)
            when (data[payloadStart].toInt() and 0x1F) {
                7 -> sps = unit
                8 -> pps = unit
            }
        }
        return sps to pps
    }

    /** From the first byte after a start code back to where that start code began. */
    private fun backToStartCode(data: ByteArray, afterStart: Int): Int =
        if (afterStart >= 4 && data[afterStart - 4] == 0.toByte() && data[afterStart - 3] == 0.toByte() && data[afterStart - 2] == 0.toByte()) afterStart - 4 else afterStart - 3
}
