package com.castlite.phone.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.Surface
import com.castlite.phone.diagnostics.Diag
import com.castlite.protocol.MirrorProtocol
import com.castlite.protocol.MirrorWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

/**
 * One mirroring connection: screen → hardware H.264 encoder → TCP → TV, plus (Android
 * 10+) the phone's media sound as raw PCM. Tuned for low delay: no B-frames, real-time
 * priority, and frames repeated on a still screen so the TV never waits.
 */
class MirrorSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val host: String,
    private val withAudio: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onStarted(width: Int, height: Int, audio: Boolean)
        fun onEnded(error: String?)
    }

    @Volatile private var running = false
    private var socket: Socket? = null
    private var writer: MirrorWriter? = null
    private var display: VirtualDisplay? = null
    private val encoderLock = Any()
    private var encoder: MediaCodec? = null
    private var width = 0
    private var height = 0
    private val handlerThread = HandlerThread("mirror-display").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var displayListener: DisplayManager.DisplayListener? = null

    fun start() {
        running = true
        Thread({ run() }, "mirror-session").start()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { writer?.write(MirrorProtocol.TYPE_END, 0, ByteArray(0)) }
        runCatching { socket?.close() }
        cleanup()
    }

    private fun run() {
        try {
            val s = Socket().apply {
                tcpNoDelay = true
                sendBufferSize = 512 * 1024
                connect(InetSocketAddress(host, MirrorProtocol.PORT), 5_000)
            }
            socket = s
            MirrorProtocol.writeHeader(s.getOutputStream())
            val w = MirrorWriter(s.getOutputStream())
            writer = w
            val (vw, vh, dpi) = targetSize()
            startVideo(vw, vh, dpi)
            watchRotation()
            val audio = withAudio && startAudio(w)
            listener.onStarted(vw, vh, audio)
            drainVideo(w)
        } catch (e: Exception) {
            if (running) {
                Diag.log("mirror", "Mirroring ended: ${e.message}")
                running = false
                cleanup()
                listener.onEnded("Mirroring stopped: the TV is unreachable or closed the connection.")
                return
            }
        }
        if (running) {
            running = false
            cleanup()
            listener.onEnded(null)
        }
    }

    /** Screen size scaled so the long side is 1280 px (sharp on a TV, light on Wi-Fi). */
    private fun targetSize(): Triple<Int, Int, Int> {
        val metrics = context.resources.displayMetrics
        val dm = context.getSystemService(DisplayManager::class.java)
        val real = android.graphics.Point()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(real)
        val sw = if (real.x > 0) real.x else metrics.widthPixels
        val sh = if (real.y > 0) real.y else metrics.heightPixels
        val scale = LONG_SIDE.toFloat() / maxOf(sw, sh)
        fun align(v: Float) = ((v / 16f).roundToInt() * 16).coerceAtLeast(16)
        return Triple(align(sw * scale), align(sh * scale), metrics.densityDpi)
    }

    private fun createEncoder(w: Int, h: Int): MediaCodec {
        fun format(baseline: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep sending frames on a still screen so the TV always has a fresh picture.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (baseline) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format(baseline = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject explicit profiles; fall back to their defaults.
            codec.reset()
            codec.configure(format(baseline = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return codec
    }

    private fun startVideo(w: Int, h: Int, dpi: Int) {
        val codec = createEncoder(w, h)
        val surface: Surface = codec.createInputSurface()
        codec.start()
        synchronized(encoderLock) {
            encoder = codec
            width = w
            height = h
        }
        display = projection.createVirtualDisplay(
            "CastLite mirror", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler,
        )
    }

    /** Phone rotated: new encoder at the new shape; the virtual display is resized (not recreated). */
    private fun restartVideo() {
        val (w, h, dpi) = targetSize()
        if (w == width && h == height) return
        Diag.log("mirror", "Rotation: ${width}x$height → ${w}x$h")
        val codec = createEncoder(w, h)
        val surface = codec.createInputSurface()
        codec.start()
        val old: MediaCodec?
        synchronized(encoderLock) {
            old = encoder
            encoder = codec
            width = w
            height = h
        }
        display?.resize(w, h, dpi)
        display?.surface = surface
        old?.let { runCatching { it.stop() }; runCatching { it.release() } }
    }

    private fun watchRotation() {
        val dm = context.getSystemService(DisplayManager::class.java)
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && running) runCatching { restartVideo() }
            }
        }
        displayListener = l
        dm.registerDisplayListener(l, handler)
    }

    /** Sends encoder output (codec config + frames) to the TV until stopped. */
    private fun drainVideo(w: MirrorWriter) {
        val info = MediaCodec.BufferInfo()
        var sentConfigFor: MediaCodec? = null
        while (running) {
            val codec = synchronized(encoderLock) { encoder } ?: break
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                continue // encoder is being swapped after a rotation
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Some encoders report SPS/PPS here instead of as a config buffer.
                val f = codec.outputFormat
                val sps = f.getByteBuffer("csd-0")
                val pps = f.getByteBuffer("csd-1")
                if (sps != null && pps != null && sentConfigFor !== codec) {
                    val csd = ByteArray(sps.remaining() + pps.remaining())
                    sps.get(csd, 0, sps.remaining())
                    pps.get(csd, csd.size - pps.remaining(), pps.remaining())
                    w.write(MirrorProtocol.TYPE_VIDEO_CONFIG, 0, MirrorProtocol.videoConfigPayload(width, height, csd))
                    sentConfigFor = codec
                }
                continue
            }
            if (index < 0) continue
            try {
                val buf = codec.getOutputBuffer(index) ?: continue
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes, 0, info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    if (sentConfigFor !== codec) {
                        w.write(MirrorProtocol.TYPE_VIDEO_CONFIG, 0, MirrorProtocol.videoConfigPayload(width, height, bytes))
                        sentConfigFor = codec
                    }
                } else if (info.size > 0) {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    w.write(MirrorProtocol.TYPE_VIDEO_FRAME, info.presentationTimeUs, bytes, flags = if (key) MirrorProtocol.FLAG_KEYFRAME else 0)
                }
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }
    }

    /** Captures what apps play (Android 10+, apps can opt out) and streams it as PCM. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO checked by MirrorService before asking for audio
    private fun startAudio(w: MirrorWriter): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, CHUNK * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record.startRecording()
            w.write(MirrorProtocol.TYPE_AUDIO_CONFIG, 0, MirrorProtocol.audioConfigPayload(SAMPLE_RATE, 2))
            Thread({
                val buf = ByteArray(CHUNK)
                try {
                    while (running) {
                        val n = record.read(buf, 0, buf.size)
                        if (n > 0) w.write(MirrorProtocol.TYPE_AUDIO_PCM, System.nanoTime() / 1000, buf, 0, n)
                    }
                } catch (e: Exception) {
                    // socket closed: the video loop reports it
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
            }, "mirror-audio").start()
            true
        } catch (e: Exception) {
            Diag.log("mirror", "No sound: ${e.message}")
            false
        }
    }

    private fun cleanup() {
        displayListener?.let { runCatching { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) } }
        displayListener = null
        runCatching { display?.release() }
        display = null
        synchronized(encoderLock) {
            encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            encoder = null
        }
        handlerThread.quitSafely()
    }

    private companion object {
        const val LONG_SIDE = 1280
        const val BITRATE = 6_000_000
        const val SAMPLE_RATE = 48_000
        /** 10 ms of 48 kHz stereo 16-bit audio. */
        const val CHUNK = 1920
    }
}
