package com.castlite.phone.cast

import com.castlite.phone.media.CastMedia
import com.castlite.protocol.ReceiverProtocol
import kotlinx.serialization.Serializable

/** The ways CastLite can talk to a TV. Shown in the UI so you know which one is in use. */
@Serializable
enum class CastProtocol(val label: String, val shortLabel: String) {
    /** UPnP AV / DLNA MediaRenderer — built into LG webOS, Samsung, Sony, Philips, many boxes. */
    DLNA("DLNA / UPnP", "DLNA"),

    /** The CastLite TV app (tv module) — any Android TV. */
    CASTLITE("CastLite TV app", "CastLite"),

    /** Reserved: see cast/googlecast/GoogleCastSupport.kt. Not implemented. */
    GOOGLE_CAST("Google Cast", "Cast"),
}

/** Control URLs of a DLNA renderer, read from its device description XML. */
@Serializable
data class DlnaEndpoints(
    val descriptionUrl: String,
    val avTransportUrl: String,
    val renderingControlUrl: String? = null,
)

/** A TV (or other renderer) found on the network or entered by hand. */
@Serializable
data class CastDevice(
    /** Stable id: the UPnP UDN for DLNA, `castlite:<host>` for the receiver app. */
    val id: String,
    val name: String,
    val protocol: CastProtocol,
    val host: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val dlna: DlnaEndpoints? = null,
    val receiverPort: Int = ReceiverProtocol.PORT,
    /** Added with "Enter TV IP" rather than discovered. */
    val isManual: Boolean = false,
) {
    /** "LG Electronics · OLED55C1" style subtitle. */
    val details: String
        get() = listOfNotNull(manufacturer, model).distinct().joinToString(" · ").ifEmpty { host }
}

/** One queue item as sent to a TV: the picked file plus the URL the TV must fetch. */
data class QueueEntry(val media: CastMedia, val url: String)

enum class PlayerState { IDLE, CONNECTING, LOADING, PLAYING, PAUSED, ENDED, ERROR }

/** What the TV is doing right now, as last reported. */
data class PlaybackStatus(
    val state: PlayerState = PlayerState.IDLE,
    val index: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0..100, or null if the TV doesn't report volume. */
    val volume: Int? = null,
    /** Readable problem description when [state] is ERROR. */
    val error: String? = null,
) {
    val isActive: Boolean get() = state == PlayerState.PLAYING || state == PlayerState.LOADING || state == PlayerState.CONNECTING
}

/** A failure with a message that can be shown to the user as-is. */
class CastException(message: String, cause: Throwable? = null) : Exception(message, cause)
