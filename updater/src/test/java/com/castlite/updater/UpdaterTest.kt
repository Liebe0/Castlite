package com.castlite.updater

import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

class UpdaterTest {

    private val apk = Random(7).nextBytes(300_000)
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private lateinit var server: NanoHTTPD
    private lateinit var dir: File
    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    @Before fun setUp() {
        dir = createTempDir()
        server = object : NanoHTTPD(0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                // GitHub answers release downloads with a redirect to its CDN.
                "/releases/latest/download/update.json" -> newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
                    addHeader("Location", "$base/cdn/update.json")
                }
                "/cdn/update.json" -> newFixedLengthResponse(Response.Status.OK, "application/json", manifestJson())
                "/cdn/phone.apk" -> newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", apk.inputStream(), apk.size.toLong())
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "nope")
            }
        }.apply { start(5_000, true) }
    }

    @After fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun manifestJson() = """{"versionCode":142,"versionName":"1.1.42","notes":"Fix","commit":"abc1234",
        "phone":{"url":"$base/cdn/phone.apk","sha256":"$apkSha","size":${apk.size}},"extra":"ignored"}"""

    @Test fun parsesManifestAndComparesVersions() {
        val m = UpdateManifest.parse(manifestJson())
        assertEquals(142, m.versionCode)
        assertTrue(m.isUpdateFor(AppTarget.PHONE, installedVersionCode = 3))
        assertFalse(m.isUpdateFor(AppTarget.PHONE, installedVersionCode = 142)) // same version
        assertFalse(m.isUpdateFor(AppTarget.PHONE, installedVersionCode = 150)) // newer installed
        assertFalse(m.isUpdateFor(AppTarget.TV, installedVersionCode = 3)) // no TV APK in this release
    }

    @Test fun fetchesManifestThroughRedirect() {
        val m = UpdateDownloader().fetchManifest("$base/releases/latest/download/update.json")
        assertEquals("1.1.42", m.versionName)
    }

    @Test fun missingReleaseIsReadable() {
        try {
            UpdateDownloader().fetchManifest("$base/nothing.json")
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("No release published yet", e.message)
        }
    }

    @Test fun downloadsAndVerifiesApk() {
        val m = UpdateManifest.parse(manifestJson())
        val dest = File(dir, "u.apk")
        var lastProgress = 0f
        UpdateDownloader().download(m.phone!!, dest) { lastProgress = it }
        assertArrayEquals(apk, dest.readBytes())
        assertEquals(1f, lastProgress)
    }

    @Test fun rejectsTamperedDownload() {
        val asset = UpdateManifest.parse(manifestJson()).phone!!.copy(sha256 = "00".repeat(32))
        val dest = File(dir, "bad.apk")
        try {
            UpdateDownloader().download(asset, dest)
            fail("expected checksum failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("checksum"))
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun rejectsTruncatedDownload() {
        val asset = UpdateManifest.parse(manifestJson()).phone!!.copy(size = apk.size + 10L)
        try {
            UpdateDownloader().download(asset, File(dir, "short.apk"))
            fail("expected size failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
    }

    @Test fun reusesCompletedDownload() {
        val m = UpdateManifest.parse(manifestJson())
        val dest = File(dir, "u.apk")
        UpdateDownloader().download(m.phone!!, dest)
        server.stop() // a second download would fail now
        UpdateDownloader().download(m.phone!!, dest)
        assertArrayEquals(apk, dest.readBytes())
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(): File = kotlin.io.createTempDir("castlite-upd")
}
