package com.castlite.phone.diagnostics

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory log of what CastLite did (discovery, TV commands, errors…), included in
 * "Send diagnostics" so real-device problems can be fixed without a debugger.
 */
object Diag {
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        // Logcat is a bonus; never let logging break casting (or JVM tests without Android).
        try {
            Log.i("CastLite/$tag", message)
        } catch (e: Throwable) {
            // no logcat available
        }
        synchronized(lines) {
            lines.addLast("${time.format(Date())} [$tag] $message")
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }
}
