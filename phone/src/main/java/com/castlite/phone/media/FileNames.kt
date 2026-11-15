package com.castlite.phone.media

/**
 * Pure helpers for building URL-safe file names. Kept free of Android APIs so they
 * can be unit-tested on the JVM.
 */
object FileNames {

    /**
     * Turns a display name like "Família 2024 (1).MOV" into "Fam_lia_2024_1_.MOV":
     * only ASCII letters, digits, '.', '-' and '_' survive, so the URL never needs
     * escaping (several DLNA renderers mishandle %-escapes).
     *
     * @param extensionForMime fallback extension (without dot) used when the
     *        display name has none, e.g. "mp4" for "video/mp4".
     */
    fun sanitize(displayName: String, extensionForMime: String?): String {
        var base = displayName.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.')
            .take(80)
        if (base.isEmpty()) base = "media"
        val hasExtension = base.substringAfterLast('.', "").let { it.isNotEmpty() && it.length <= 5 && it != base }
        if (!hasExtension && !extensionForMime.isNullOrEmpty()) base += ".$extensionForMime"
        return base
    }
}
