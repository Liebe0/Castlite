package com.castlite.phone.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap

/** Reads name, size and MIME type of content:// URIs coming from the system picker. */
class MediaResolver(context: Context) {

    private val context = context.applicationContext
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * Builds a [CastMedia] for a picked URI, or null if the file can't be read.
     * Also takes a persistable read grant so the URI stays readable by the service
     * even after the picker's temporary grant would have expired.
     */
    fun resolve(uri: Uri): CastMedia? {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not every provider offers persistable grants; the temporary one still works.
            Log.d(TAG, "No persistable permission for $uri")
        }

        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query $uri", e)
            return null
        }

        // Some providers don't report SIZE; fall back to the file descriptor's length.
        if (size < 0) size = sizeFromDescriptor(uri)

        val displayName = name ?: uri.lastPathSegment ?: "media"
        val mime = MimeTypes.normalize(resolver.getType(uri), displayName)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val kind = MediaKind.fromMime(mime)
        if (kind == MediaKind.OTHER) {
            Log.w(TAG, "Unsupported type $mime for $displayName")
            return null
        }
        val duration = if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) durationOf(uri) else 0L

        return CastMedia(
            id = MediaLibrary.newId(),
            contentUri = uri.toString(),
            displayName = displayName,
            fileName = FileNames.sanitize(displayName, ext),
            mimeType = mime,
            sizeBytes = size,
            durationMs = duration,
        )
    }

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

    private fun sizeFromDescriptor(uri: Uri): Long = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private companion object {
        const val TAG = "MediaResolver"
    }
}
