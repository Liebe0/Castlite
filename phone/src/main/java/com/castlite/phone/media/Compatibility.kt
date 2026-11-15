package com.castlite.phone.media

/**
 * What a TV says (or is known) to support. All fields nullable = "unknown".
 *  - CastLite TV app: real decoder list from the TV ([videoCodecs], [audioCodecs], …).
 *  - DLNA TVs: the formats they accept ([containers], from ConnectionManager GetProtocolInfo).
 */
data class TvSupport(
    val containers: Set<String>? = null,
    val videoCodecs: Set<String>? = null,
    val audioCodecs: Set<String>? = null,
    val hevcMain10: Boolean? = null,
    val maxVideoHeight: Int? = null,
    val isDlna: Boolean = false,
)

/** "Will this file play on that TV?" — a warning, never a block (TVs can surprise both ways). */
data class Compatibility(val problems: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
    val summary: String? get() = problems.firstOrNull()?.let { if (problems.size > 1) "$it (+${problems.size - 1} more)" else it }

    companion object {
        val OK = Compatibility(emptyList())

        /** Audio codecs most TVs dropped (LG since 2020, Samsung, many Android TVs without licences). */
        private val RARELY_SUPPORTED_AUDIO = setOf("audio/vnd.dts", "audio/vnd.dts.hd", "audio/true-hd", "audio/vnd.dts.uhd")

        fun check(media: CastMedia, tv: TvSupport?): Compatibility {
            if (tv == null || media.isRemote) return OK
            val problems = mutableListOf<String>()
            val info = media.info

            // Container / file type (DLNA renderers list what they accept).
            tv.containers?.let { accepted ->
                if (accepted.isNotEmpty() && !acceptsContainer(accepted, media.mimeType)) {
                    problems += "${fileTypeName(media.mimeType)} files aren't on this TV's supported list"
                }
            }
            if (info != null) {
                // Video codec.
                val video = info.videoCodec
                if (video != null) {
                    tv.videoCodecs?.let { codecs ->
                        if (codecs.isNotEmpty() && video !in codecs) problems += "${codecName(video)} video isn't supported"
                    }
                    if (video == "video/hevc" && info.tenBit && tv.hevcMain10 == false) {
                        problems += "10-bit (HDR) HEVC video isn't supported"
                    }
                    tv.maxVideoHeight?.let { max ->
                        if (info.height > 0 && minOf(info.width, info.height) > max) problems += "${info.height}p is more than this TV handles (${max}p)"
                    }
                }
                // Audio: fine if at least one track is playable.
                if (info.audioCodecs.isNotEmpty()) {
                    val known = tv.audioCodecs
                    val playable = when {
                        known != null && known.isNotEmpty() -> info.audioCodecs.any { it in known }
                        else -> info.audioCodecs.any { it !in RARELY_SUPPORTED_AUDIO }
                    }
                    if (!playable) {
                        problems += "${codecName(info.audioCodecs.first())} audio probably won't play" +
                            if (media.kind == MediaKind.VIDEO) " (video without sound)" else ""
                    }
                }
            }
            return Compatibility(problems)
        }

        private fun acceptsContainer(accepted: Set<String>, mime: String): Boolean {
            if ("*" in accepted || "*/*" in accepted) return true
            val aliases = when (mime) {
                "video/x-matroska" -> setOf("video/x-matroska", "video/x-mkv", "video/mkv")
                "audio/mp4" -> setOf("audio/mp4", "audio/x-m4a", "audio/m4a")
                "video/quicktime" -> setOf("video/quicktime", "video/mp4")
                "audio/wav" -> setOf("audio/wav", "audio/x-wav", "audio/l16")
                "audio/flac" -> setOf("audio/flac", "audio/x-flac")
                "video/x-msvideo" -> setOf("video/x-msvideo", "video/avi", "video/x-avi")
                else -> setOf(mime)
            }
            return aliases.any { it in accepted }
        }

        fun codecName(mime: String): String = when (mime) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265 (HEVC)"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/av01" -> "AV1"
            "video/mp4v-es" -> "MPEG-4"
            "video/mpeg2" -> "MPEG-2"
            "video/dolby-vision" -> "Dolby Vision"
            "audio/mp4a-latm" -> "AAC"
            "audio/mpeg" -> "MP3"
            "audio/ac3" -> "Dolby Digital (AC-3)"
            "audio/eac3" -> "Dolby Digital Plus"
            "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
            "audio/true-hd" -> "Dolby TrueHD"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            else -> mime.substringAfter('/').uppercase()
        }

        private fun fileTypeName(mime: String): String = when (mime) {
            "video/x-matroska" -> "MKV"
            "video/quicktime" -> "MOV"
            "video/x-msvideo" -> "AVI"
            "video/webm" -> "WebM"
            else -> mime.substringAfter('/').uppercase()
        }

        /** "Sink" CSV from DLNA GetProtocolInfo → accepted MIME types. */
        fun parseSinkProtocolInfo(sink: String): Set<String> = sink.split(',')
            .mapNotNull { entry -> entry.trim().split(':').getOrNull(2)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } }
            .toSet()
    }
}
