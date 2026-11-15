package com.castlite.phone.media

/**
 * Normalizes MIME types to the spellings TVs recognise. Android providers sometimes
 * report non-standard ones (e.g. "audio/mp3"), and DLNA renderers compare the type
 * literally — a mismatch means "unsupported format" on the TV.
 */
object MimeTypes {
    private val aliases = mapOf(
        "audio/mp3" to "audio/mpeg",
        "audio/x-mp3" to "audio/mpeg",
        "audio/mpeg3" to "audio/mpeg",
        "audio/x-m4a" to "audio/mp4",
        "audio/m4a" to "audio/mp4",
        "audio/aac-adts" to "audio/aac",
        "audio/x-wav" to "audio/wav",
        "audio/vnd.wave" to "audio/wav",
        "audio/x-flac" to "audio/flac",
        "video/x-matroska" to "video/x-matroska",
        "video/mkv" to "video/x-matroska",
        "video/avi" to "video/x-msvideo",
        "video/x-ms-asf" to "video/x-ms-wmv",
        "image/jpg" to "image/jpeg",
        "image/pjpeg" to "image/jpeg",
    )

    private val byExtension = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "ts" to "video/mp2t", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/ogg",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
    )

    /**
     * @param reported MIME type from the content provider (may be null or generic).
     * @param fileName used to guess when the provider says nothing useful.
     */
    fun normalize(reported: String?, fileName: String): String {
        val clean = reported?.substringBefore(';')?.trim()?.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (clean.isNullOrEmpty() || clean == "application/octet-stream" || clean == "*/*") {
            return byExtension[ext] ?: "application/octet-stream"
        }
        return aliases[clean] ?: clean
    }
}
