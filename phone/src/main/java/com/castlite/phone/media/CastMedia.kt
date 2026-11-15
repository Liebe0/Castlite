package com.castlite.phone.media

import android.net.Uri
import kotlinx.serialization.Serializable

/** What kind of content a picked file is. Drives how a receiver should present it. */
enum class MediaKind { VIDEO, IMAGE, AUDIO, OTHER;

    companion object {
        fun fromMime(mime: String): MediaKind = when {
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("image/") -> IMAGE
            mime.startsWith("audio/") -> AUDIO
            mime.contains("mpegurl", ignoreCase = true) -> VIDEO // HLS playlists
            else -> OTHER
        }
    }
}

/** An external subtitle file (.srt or .vtt) attached to a video. */
@Serializable
data class SubtitleRef(
    val contentUri: String,
    val name: String,
    /** "application/x-subrip" or "text/vtt". */
    val mimeType: String,
) {
    val extension: String get() = if (mimeType.contains("vtt")) "vtt" else "srt"
}

/** Codecs inside a video/audio file, read on the phone to warn about TVs that can't play it. */
@Serializable
data class MediaInfo(
    /** e.g. "video/avc", "video/hevc". */
    val videoCodec: String? = null,
    /** Every audio track, e.g. ["audio/mp4a-latm", "audio/ac3"]. */
    val audioCodecs: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    /** 10-bit HEVC (typical for HDR phone videos). */
    val tenBit: Boolean = false,
)

/**
 * A file (or web link) in the queue.
 *
 * @param id       random, unguessable token used in the URL (`/media/<id>/<fileName>`).
 *                 Only files with a registered id can be fetched from the phone.
 * @param contentUri content:// URI from the picker, or an http(s) link for web media
 *                 (the TV then fetches it directly, not through the phone).
 * @param fileName sanitized name (ASCII, with extension) placed at the end of the URL.
 *                 Some TVs pick a demuxer from the URL extension, so it must be right.
 * @param sizeBytes file size, or -1 if unknown (then seeking is impossible).
 */
@Serializable
data class CastMedia(
    val id: String,
    val contentUri: String,
    val displayName: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Playback length for video/audio (0 if unknown or a photo). */
    val durationMs: Long = 0,
    val subtitle: SubtitleRef? = null,
    val info: MediaInfo? = null,
) {
    val uri: Uri get() = Uri.parse(contentUri)

    val kind: MediaKind get() = MediaKind.fromMime(mimeType)

    /** A web link the TV streams directly (not served by the phone). */
    val isRemote: Boolean get() = contentUri.startsWith("http://", true) || contentUri.startsWith("https://", true)

    /** Path part of the URL, e.g. `/media/k3j9x2a1/holiday.mp4`. */
    val path: String get() = "/media/$id/$fileName"

    /** Where the phone serves this item's subtitles, e.g. `/media/k3j9x2a1/subtitle.srt`. */
    val subtitlePath: String? get() = subtitle?.let { "/media/$id/subtitle.${it.extension}" }
}
