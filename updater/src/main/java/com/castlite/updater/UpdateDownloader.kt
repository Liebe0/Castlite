package com.castlite.updater

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Network part of the updater, kept free of Android APIs so it can be unit-tested.
 * Blocking: call from a background dispatcher.
 */
class UpdateDownloader(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) {

    fun fetchManifest(url: String): UpdateManifest {
        val conn = open(url)
        try {
            if (conn.responseCode == 404) throw IOException("No release published yet")
            if (conn.responseCode != 200) throw IOException("Update server replied HTTP ${conn.responseCode}")
            return UpdateManifest.parse(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads [asset] to [dest] and checks its size and SHA-256. A partial or
     * tampered download is deleted and reported as an [IOException].
     */
    fun download(asset: UpdateAsset, dest: File, onProgress: (Float) -> Unit = {}) {
        // Reuse an earlier complete download of the same file.
        if (dest.exists() && dest.length() == asset.size && sha256(dest) == asset.sha256.lowercase()) {
            onProgress(1f)
            return
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val conn = open(asset.url)
        try {
            if (conn.responseCode != 200) throw IOException("Download failed: HTTP ${conn.responseCode}")
            val total = asset.size.takeIf { it > 0 } ?: conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val hash = digest.digest().toHex()
            if (asset.size > 0 && done != asset.size) throw IOException("Download incomplete ($done of ${asset.size} bytes)")
            if (hash != asset.sha256.lowercase()) throw IOException("Downloaded file is corrupted (checksum mismatch)")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the update")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true // GitHub redirects release downloads to its CDN
            useCaches = false
            setRequestProperty("User-Agent", "CastLite-Updater")
        }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
