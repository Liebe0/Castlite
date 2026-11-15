package com.castlite.phone.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.castlite.phone.cast.CastProtocol
import com.castlite.phone.cast.dlna.DlnaController
import com.castlite.phone.ui.MainUiState
import com.castlite.protocol.ReceiverClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Send diagnostics": one text file with everything needed to fix a real-device
 * problem — phone, network, the selected TV (its own log for the CastLite TV app, its
 * description and supported formats for DLNA), the queue, and CastLite's recent log.
 * Blocking (fetches from the TV): call on Dispatchers.IO.
 */
object DiagnosticsReport {

    fun create(context: Context, state: MainUiState): Intent {
        val text = build(context, state)
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the newest report
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "castlite-diagnostics-$stamp.txt").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "CastLite diagnostics $stamp")
            .putExtra(Intent.EXTRA_TEXT, "CastLite ${state.appVersion} diagnostics (file attached).")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Send CastLite diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun build(context: Context, s: MainUiState): String = buildString {
        appendLine("=== CastLite diagnostics ===")
        appendLine("Time: ${Date()}")
        appendLine("App: ${s.appVersion}")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Network: ${s.lan.kind} ${s.lan.ipAddress}")
        appendLine("Server: ${s.server}")
        appendLine("Background: ${s.power}")
        appendLine("Update: ${s.update}")
        appendLine("Mirror: ${s.mirror}")
        appendLine("Selected TV: ${s.selectedDevice}")
        appendLine("Other TVs: ${s.extraDevices.map { it.name }}")
        appendLine("TV support: ${s.support}")
        appendLine("Session: ${s.session?.let { "${it.devicesLabel} ${it.status} problem=${it.problem}" }}")
        appendLine("Repeat: ${s.repeat}, slideshow: ${s.slideshow}")
        appendLine()
        appendLine("=== Queue (${s.media.size}) ===")
        s.media.forEachIndexed { i, m ->
            appendLine("$i. ${m.displayName} | ${m.mimeType} | ${m.sizeBytes} B | ${m.durationMs} ms | ${m.info} | subs=${m.subtitle?.name} | remote=${m.isRemote}")
            val compat = s.compatibility(m)
            if (!compat.ok) appendLine("   ! ${compat.problems.joinToString("; ")}")
        }
        appendLine()
        s.selectedDevice?.let { tv ->
            appendLine("=== TV: ${tv.name} (${tv.protocol}) ===")
            when (tv.protocol) {
                CastProtocol.CASTLITE -> append(ReceiverClient(tv.host, tv.receiverPort, 3_000, 5_000).diagnostics())
                CastProtocol.DLNA -> tv.dlna?.let { e ->
                    appendLine("Accepted types: ${DlnaController.supportedTypes(e)}")
                    appendLine("Description (${e.descriptionUrl}):")
                    appendLine(fetch(e.descriptionUrl).take(12_000))
                }
                CastProtocol.GOOGLE_CAST -> Unit
            }
            appendLine()
        }
        appendLine()
        appendLine("=== CastLite log ===")
        append(Diag.dump())
    }

    private fun fetch(url: String): String = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 3_000; readTimeout = 4_000 }
        try {
            conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        "(couldn't fetch: ${e.message})"
    }
}
