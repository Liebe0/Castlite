package com.castlite.phone.media

import android.net.Uri

/** What kind of content a picked file is. Drives how a receiver should present it. */
enum class MediaKind { VIDEO, IMAGE, AUDIO, OTHER;

    companion object {
        fun fromMime(mime: String): MediaKind = when {
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("image/") -> IMAGE
            mime.startsWith("audio/") -> AUDIO
            else -> OTHER
        }
    }
}

/**
 * A file the user picked and that the local HTTP server is allowed to serve.
 *
 * @param id       random, unguessable token used in the URL (`/media/<id>/<fileName>`).
 *                 Only files with a registered id can be fetched from the phone.
 * @param contentUri content:// URI returned by the system picker, as a string (keeps
 *                 this class free of Android types so the server can be JVM-tested).
 * @param fileName sanitized name (ASCII, with extension) placed at the end of the URL.
 *                 Some TVs pick a demuxer from the URL extension, so it must be right.
 * @param sizeBytes file size, or -1 if the provider didn't report one (then seeking
 *                 is impossible and the file is streamed start to end).
 */
data class CastMedia(
    val id: String,
    val contentUri: String,
    val displayName: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    val uri: Uri get() = Uri.parse(contentUri)

    val kind: MediaKind get() = MediaKind.fromMime(mimeType)

    /** Path part of the URL, e.g. `/media/k3j9x2a1/holiday.mp4`. */
    val path: String get() = "/media/$id/$fileName"
}
