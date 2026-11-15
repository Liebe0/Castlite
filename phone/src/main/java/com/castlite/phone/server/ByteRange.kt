package com.castlite.phone.server

/**
 * Parsing of the HTTP `Range` request header (RFC 9110 §14), limited to what media
 * players actually send: a single byte range. Pure Kotlin so it's unit-testable.
 *
 * Players seek by asking for e.g. `Range: bytes=1048576-`; the server answers
 * `206 Partial Content` with only those bytes. Without this, seeking can't work.
 */
sealed interface RangeRequest {
    /** No (usable) Range header: send the whole file with 200 OK. */
    data object Full : RangeRequest

    /** Send bytes [start]..[endInclusive] with 206 Partial Content. */
    data class Partial(val start: Long, val endInclusive: Long) : RangeRequest {
        val length: Long get() = endInclusive - start + 1
    }

    /** The range starts beyond the end of the file: 416 Range Not Satisfiable. */
    data object Unsatisfiable : RangeRequest

    companion object {
        /**
         * @param header raw `Range` header value, or null if absent.
         * @param totalSize file size in bytes; ranges are impossible when unknown (< 0).
         */
        fun parse(header: String?, totalSize: Long): RangeRequest {
            if (header.isNullOrBlank() || totalSize < 0) return Full
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return Full
            val spec = value.substring(6).trim()
            // Multiple ranges would need a multipart/byteranges reply. The RFC allows
            // ignoring Range instead, and no media player we care about sends them.
            if (spec.contains(',')) return Full

            val dash = spec.indexOf('-')
            if (dash < 0) return Full
            val startText = spec.substring(0, dash).trim()
            val endText = spec.substring(dash + 1).trim()

            if (startText.isEmpty()) {
                // Suffix range "bytes=-500": the last 500 bytes.
                val suffix = endText.toLongOrNull() ?: return Full
                if (suffix <= 0) return Unsatisfiable
                if (totalSize == 0L) return Unsatisfiable
                val start = (totalSize - suffix).coerceAtLeast(0)
                return Partial(start, totalSize - 1)
            }

            val start = startText.toLongOrNull() ?: return Full
            if (start < 0) return Full
            if (start >= totalSize) return Unsatisfiable
            val end = if (endText.isEmpty()) {
                totalSize - 1
            } else {
                val parsed = endText.toLongOrNull() ?: return Full
                if (parsed < start) return Full // syntactically invalid → ignore header
                parsed.coerceAtMost(totalSize - 1)
            }
            return Partial(start, end)
        }
    }
}
