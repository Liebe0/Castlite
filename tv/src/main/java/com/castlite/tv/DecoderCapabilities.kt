package com.castlite.tv

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.castlite.protocol.Capabilities

/**
 * Reads which codecs this TV can decode in hardware/software, so the phone can warn
 * "this file won't play here" *before* casting.
 */
object DecoderCapabilities {

    val current: Capabilities by lazy { read() }

    private fun read(): Capabilities = try {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val types = decoders.flatMap { it.supportedTypes.map(String::lowercase) }.toSortedSet()
        val hevc10 = decoders.any { info ->
            info.supportedTypes.any { it.equals("video/hevc", true) } && runCatching {
                info.getCapabilitiesForType("video/hevc").profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                }
            }.getOrDefault(false)
        }
        val maxHeight = decoders.flatMap { info ->
            info.supportedTypes.filter { it.startsWith("video/") }.mapNotNull { type ->
                runCatching { info.getCapabilitiesForType(type).videoCapabilities?.supportedHeights?.upper }.getOrNull()
            }
        }.maxOrNull() ?: 1080
        Capabilities(
            videoCodecs = types.filter { it.startsWith("video/") },
            audioCodecs = types.filter { it.startsWith("audio/") },
            hevcMain10 = hevc10,
            maxVideoHeight = maxHeight,
        )
    } catch (e: Exception) {
        TvLog.log("caps", "Couldn't read decoders: ${e.message}")
        Capabilities()
    }
}
