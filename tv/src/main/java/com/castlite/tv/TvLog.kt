package com.castlite.tv

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Small in-memory log of what the receiver did; sent to the phone for "Send diagnostics". */
object TvLog {
    private const val MAX = 400
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
