package com.castlite.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Screen-mirroring stream (phone → CastLite TV app), one TCP connection:
 *
 * ```
 * "CLMR" + version(1 byte)
 * then frames:  type(1) flags(1) ptsUs(8) length(4) payload(length)
 * ```
 * Video is H.264 (Annex-B) straight from the phone's hardware encoder; the config frame
 * carries SPS/PPS and the picture size. Audio is raw 16-bit PCM (cheap on a LAN and
 * needs no encoder). Big-endian throughout.
 */
object MirrorProtocol {
    const val PORT = 8767
    const val VERSION = 1
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte())

    /** width(4) height(4) + SPS/PPS in Annex-B. Sent first and after every size change. */
    const val TYPE_VIDEO_CONFIG = 1
    const val TYPE_VIDEO_FRAME = 2
    /** sampleRate(4) channels(4). */
    const val TYPE_AUDIO_CONFIG = 3
    const val TYPE_AUDIO_PCM = 4
    /** The phone stopped mirroring. */
    const val TYPE_END = 5

    const val FLAG_KEYFRAME = 1

    /** Refuse absurd frame sizes (protects the TV from a broken or hostile stream). */
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    fun writeHeader(out: OutputStream) {
        out.write(MAGIC)
        out.write(VERSION)
        out.flush()
    }

    fun readHeader(input: InputStream) {
        val buf = ByteArray(5)
        DataInputStream(input).readFully(buf)
        if (!buf.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("Not a CastLite mirror stream")
        if (buf[4].toInt() > VERSION) throw IOException("Mirror stream version ${buf[4]} is newer than this app")
    }

    fun videoConfigPayload(width: Int, height: Int, csd: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(8 + csd.size).putInt(width).putInt(height).put(csd).array()

    fun audioConfigPayload(sampleRate: Int, channels: Int): ByteArray =
        java.nio.ByteBuffer.allocate(8).putInt(sampleRate).putInt(channels).array()
}

data class MirrorFrame(val type: Int, val flags: Int, val ptsUs: Long, val data: ByteArray) {
    val isKeyframe: Boolean get() = flags and MirrorProtocol.FLAG_KEYFRAME != 0

    /** For TYPE_VIDEO_CONFIG / TYPE_AUDIO_CONFIG: the two leading ints. */
    fun configInts(): Pair<Int, Int> = java.nio.ByteBuffer.wrap(data).let { it.int to it.int }

    /** For TYPE_VIDEO_CONFIG: the SPS/PPS bytes after width/height. */
    fun videoCsd(): ByteArray = data.copyOfRange(8, data.size)

    override fun equals(other: Any?) = other is MirrorFrame && type == other.type && flags == other.flags &&
        ptsUs == other.ptsUs && data.contentEquals(other.data)

    override fun hashCode() = 31 * (31 * type + ptsUs.hashCode()) + data.contentHashCode()
}

/** Thread-safe frame writer (video and audio come from different threads). */
class MirrorWriter(out: OutputStream) {
    private val out = DataOutputStream(out.buffered(64 * 1024))

    @Synchronized
    fun write(type: Int, ptsUs: Long, data: ByteArray, offset: Int = 0, length: Int = data.size, flags: Int = 0) {
        out.writeByte(type)
        out.writeByte(flags)
        out.writeLong(ptsUs)
        out.writeInt(length)
        out.write(data, offset, length)
        out.flush()
    }
}

class MirrorReader(input: InputStream) {
    private val input = DataInputStream(input.buffered(64 * 1024))

    /** Next frame, or null at a clean end of stream. */
    fun next(): MirrorFrame? {
        val type = try {
            input.readUnsignedByte()
        } catch (e: EOFException) {
            return null
        }
        val flags = input.readUnsignedByte()
        val pts = input.readLong()
        val length = input.readInt()
        if (length < 0 || length > MirrorProtocol.MAX_FRAME_BYTES) throw IOException("Bad mirror frame length $length")
        val data = ByteArray(length)
        input.readFully(data)
        return MirrorFrame(type, flags, pts, data)
    }
}
