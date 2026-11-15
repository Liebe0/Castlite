package com.castlite.phone.data

import android.content.Context

/** When it's worth remembering where you stopped (pure logic, unit-tested). */
object ResumePolicy {
    private const val MIN_DURATION_MS = 3 * 60_000L
    private const val EDGE_MS = 30_000L

    /** Remember positions only in longer items, and not at the very start or end. */
    fun shouldRemember(positionMs: Long, durationMs: Long): Boolean =
        durationMs >= MIN_DURATION_MS && positionMs >= EDGE_MS && positionMs <= durationMs - EDGE_MS

    /** Treat "stopped in the last 30 s" as finished. */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs > durationMs - EDGE_MS
}

/** "Resume from 12:34?" — last position per file, kept on the phone. */
class ResumeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun get(contentUri: String): Long = prefs.getLong(contentUri, 0L)

    fun save(contentUri: String, positionMs: Long, durationMs: Long) {
        when {
            ResumePolicy.shouldRemember(positionMs, durationMs) -> prefs.edit().putLong(contentUri, positionMs).apply()
            ResumePolicy.isFinished(positionMs, durationMs) -> clear(contentUri)
        }
    }

    fun clear(contentUri: String) = prefs.edit().remove(contentUri).apply()
}
