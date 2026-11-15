package com.castlite.phone.server

import com.castlite.phone.media.MediaKind

/**
 * DLNA-specific HTTP header values. Strict renderers (LG webOS, Samsung, many Sony
 * models) refuse to play or to seek if these are missing or wrong. Phase 2 reuses
 * the same strings in the DIDL-Lite `protocolInfo` attribute.
 */
object Dlna {
    /**
     * DLNA.ORG_OP=01 → byte-range seeking supported (we implement Range).
     * DLNA.ORG_CI=0  → not transcoded, original file.
     * DLNA.ORG_FLAGS → streaming transfer mode + background transfer + DLNA 1.5.
     */
    fun contentFeatures(kind: MediaKind, seekable: Boolean): String {
        val op = if (seekable) "01" else "00"
        val flags = if (kind == MediaKind.IMAGE) {
            "00900000000000000000000000000000" // interactive (images) + background
        } else {
            "01700000000000000000000000000000" // streaming + background + DLNA v1.5
        }
        return "DLNA.ORG_OP=$op;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$flags"
    }

    /** Value for `transferMode.dlna.org`: images are "Interactive", A/V is "Streaming". */
    fun transferMode(kind: MediaKind): String =
        if (kind == MediaKind.IMAGE) "Interactive" else "Streaming"
}
