package com.castlite.phone.server

import android.content.ContentResolver
import android.util.Log
import com.castlite.phone.media.CastMedia
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Opens a shared file's bytes starting at an offset. Abstracted so the server can be tested on the JVM. */
fun interface MediaOpener {
    /** Returns a stream positioned at [offset], or null if the file can't be read any more. */
    fun open(media: CastMedia, offset: Long): InputStream?
}

/** Production [MediaOpener]: reads content:// URIs through the [ContentResolver]. */
class ContentMediaOpener(private val resolver: ContentResolver) : MediaOpener {

    override fun open(media: CastMedia, offset: Long): InputStream? {
        return try {
            val afd = resolver.openAssetFileDescriptor(media.uri, "r") ?: return null
            val stream = afd.createInputStream() // closing the stream closes the descriptor
            stream.skipFully(offset)
            stream
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "File missing: ${media.contentUri}", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost: ${media.contentUri}", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "Could not seek in ${media.contentUri}", e)
            null
        }
    }

    private companion object {
        const val TAG = "ContentMediaOpener"
    }
}

/**
 * Moves the stream forward by [count] bytes. For normal files skip() is an lseek
 * and costs nothing; for pipe-backed providers (some cloud apps) it falls back to reading.
 */
fun InputStream.skipFully(count: Long) {
    var remaining = count
    var scratch: ByteArray? = null
    while (remaining > 0) {
        val skipped = try {
            skip(remaining)
        } catch (e: IOException) {
            0L // non-seekable descriptor; read instead
        }
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        val buffer = scratch ?: ByteArray(64 * 1024).also { scratch = it }
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw IOException("File ended before offset $count")
        remaining -= read
    }
}
