package com.castlite.phone.cast.dlna

/** UPnP AV time strings ("H+:MM:SS[.F+]") used by Seek, GetPositionInfo and DIDL durations. */
object UpnpTime {

    /** 83_500 ms → "00:01:23". Hours are zero-padded to two digits (LG insists on it). */
    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        return "%02d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
    }

    /** DIDL `duration` attribute form: "0:01:23.500". */
    fun formatDuration(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val s = total / 1000
        return "%d:%02d:%02d.%03d".format(s / 3600, (s / 60) % 60, s % 60, total % 1000)
    }

    /** "0:01:23.456" → 83_456; "NOT_IMPLEMENTED", "" or garbage → 0. */
    fun parse(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val parts = text.trim().split(':')
        if (parts.size != 3) return 0
        val h = parts[0].toLongOrNull() ?: return 0
        val m = parts[1].toLongOrNull() ?: return 0
        val secParts = parts[2].split('.')
        val s = secParts[0].toLongOrNull() ?: return 0
        val fraction = secParts.getOrNull(1)?.let { f ->
            // ".5" = 500 ms, ".456" = 456 ms; "1/30" style fractions are ignored.
            f.take(3).padEnd(3, '0').toLongOrNull()
        } ?: 0
        return ((h * 3600 + m * 60 + s) * 1000) + fraction
    }
}
