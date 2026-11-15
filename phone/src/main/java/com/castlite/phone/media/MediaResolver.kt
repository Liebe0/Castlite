package com.castlite.phone.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.castlite.phone.diagnostics.Diag
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Turns picked files, folders and links into [CastMedia]. Blocking: call on Dispatchers.IO. */
class MediaResolver(context: Context) {

    private val context = context.applicationContext
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * Builds a [CastMedia] for a picked URI, or null if the file can't be read.
     * Also takes a persistable read grant so the URI stays readable after a restart.
     */
    fun resolve(uri: Uri, knownName: String? = null, knownMime: String? = null, knownSize: Long = -1): CastMedia? {
        takePersistable(uri)
        var name: String? = knownName
        var size = knownSize
        if (name == null || size < 0) {
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (name == null && nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        if (size < 0 && sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query $uri", e)
                return null
            }
        }
        // Some providers don't report SIZE; fall back to the file descriptor's length.
        if (size < 0) size = sizeFromDescriptor(uri)

        val displayName = name ?: uri.lastPathSegment ?: "media"
        val mime = MimeTypes.normalize(knownMime ?: resolver.getType(uri), displayName)
        val kind = MediaKind.fromMime(mime)
        if (kind == MediaKind.OTHER) {
            Diag.log("media", "Skipped $displayName: unsupported type $mime")
            return null
        }
        val playable = kind == MediaKind.VIDEO || kind == MediaKind.AUDIO
        val media = CastMedia(
            id = MediaLibrary.newId(),
            contentUri = uri.toString(),
            displayName = displayName,
            fileName = FileNames.sanitize(displayName, MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)),
            mimeType = mime,
            sizeBytes = size,
            durationMs = if (playable) durationOf(uri) else 0L,
            info = if (playable) probe(uri) else null,
        )
        Diag.log("media", "Added ${media.displayName} ($mime, ${media.sizeBytes} B, ${media.info})")
        return media
    }

    /** A subtitle file picked for a video; null if it isn't .srt / .vtt. */
    fun resolveSubtitle(uri: Uri): SubtitleRef? {
        takePersistable(uri)
        val name = queryName(uri) ?: uri.lastPathSegment ?: return null
        val mime = subtitleMime(name) ?: return null
        return SubtitleRef(uri.toString(), name, mime)
    }

    /**
     * Every video, photo and song in a picked folder (and up to [maxDepth] levels of
     * sub-folders), sorted by name. Subtitles named like a video ("Movie.srt" next to
     * "Movie.mkv") are attached automatically.
     */
    fun resolveFolder(treeUri: Uri, maxDepth: Int = 3, limit: Int = 500): List<CastMedia> {
        takePersistable(treeUri)
        data class Doc(val uri: Uri, val name: String, val mime: String, val size: Long)
        val files = mutableListOf<Doc>()

        fun walk(parentDocId: String, depth: Int) {
            if (files.size >= limit) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && files.size < limit) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < maxDepth) walk(id, depth + 1)
                    } else {
                        files += Doc(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, mime, size)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(treeUri), 0)

        val subtitles = files.filter { subtitleMime(it.name) != null }.associateBy { it.name.substringBeforeLast('.').lowercase() }
        return files
            .filter { subtitleMime(it.name) == null }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .mapNotNull { doc ->
                val media = resolve(doc.uri, doc.name, doc.mime.takeIf { it.isNotEmpty() }, doc.size) ?: return@mapNotNull null
                val sub = subtitles[doc.name.substringBeforeLast('.').lowercase()]
                if (media.kind == MediaKind.VIDEO && sub != null) {
                    media.copy(subtitle = SubtitleRef(sub.uri.toString(), sub.name, subtitleMime(sub.name)!!))
                } else {
                    media
                }
            }
    }

    /**
     * A direct link to a video/audio/photo file or an HLS playlist. The TV streams it
     * straight from the internet. Throws [IOException] with a readable message if the
     * link is a web page rather than a media file.
     */
    fun resolveLink(link: String): CastMedia {
        val url = link.trim()
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "Links must start with http:// or https://" }
        val path = runCatching { URL(url).path }.getOrDefault("")
        val nameFromUrl = Uri.decode(path.substringAfterLast('/')).ifBlank { URL(url).host }
        var mime = MimeTypes.normalize(null, nameFromUrl).takeIf { it != "application/octet-stream" }
            ?: if (path.endsWith(".m3u8", true)) "application/vnd.apple.mpegurl" else null
        var size = -1L
        if (mime == null || mime == "application/octet-stream") {
            // Ask the server what it is.
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            try {
                val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                size = conn.contentLengthLong
                mime = when {
                    type == null -> null
                    type.contains("mpegurl") -> "application/vnd.apple.mpegurl"
                    else -> MimeTypes.normalize(type, nameFromUrl)
                }
            } finally {
                conn.disconnect()
            }
        }
        if (mime == null || MediaKind.fromMime(mime) == MediaKind.OTHER) {
            throw IOException("That link is a web page, not a video, song or photo. Use a direct link to a file (e.g. ending in .mp4 or .m3u8).")
        }
        Diag.log("media", "Added link $url ($mime)")
        return CastMedia(
            id = MediaLibrary.newId(),
            contentUri = url,
            displayName = nameFromUrl,
            fileName = FileNames.sanitize(nameFromUrl, null),
            mimeType = mime,
            sizeBytes = size,
        )
    }

    /** Still readable? (Files can be deleted, and some shared files lose access after a restart.) */
    fun isReadable(media: CastMedia): Boolean = media.isRemote || try {
        resolver.openAssetFileDescriptor(media.uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** Reads codecs, size and bit depth so the app can warn about files a TV can't play. */
    private fun probe(uri: Uri): MediaInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var video: String? = null
            var width = 0
            var height = 0
            var tenBit = false
            val audio = mutableListOf<String>()
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && video == null -> {
                        video = mime
                        width = f.intOrZero(MediaFormat.KEY_WIDTH)
                        height = f.intOrZero(MediaFormat.KEY_HEIGHT)
                        val profile = f.intOrZero(MediaFormat.KEY_PROFILE)
                        tenBit = mime == MediaFormat.MIMETYPE_VIDEO_HEVC && profile in HEVC_10BIT_PROFILES ||
                            mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
                    }
                    mime.startsWith("audio/") -> audio += mime
                }
            }
            MediaInfo(video, audio, width, height, tenBit)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrZero(key: String) = if (containsKey(key)) getInteger(key) else 0

    /** Reads the play length from the file's metadata (shown in the queue and sent to TVs). */
    private fun durationOf(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun takePersistable(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not every provider offers persistable grants; the temporary one still works.
        }
    }

    private fun sizeFromDescriptor(uri: Uri): Long = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    companion object {
        private const val TAG = "MediaResolver"
        private val HEVC_10BIT_PROFILES = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )

        fun subtitleMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> null
        }

        /** "Episode 2" before "Episode 10". */
        val NaturalOrder: Comparator<String> = Comparator { a, b ->
            val re = Regex("(\\d+)|(\\D+)")
            val pa = re.findAll(a.lowercase()).map { it.value }.toList()
            val pb = re.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.toBigInteger().compareTo(y.toBigInteger())
                } else {
                    x.compareTo(y)
                }
                if (c != 0) return@Comparator c
            }
            pa.size - pb.size
        }
    }
}
