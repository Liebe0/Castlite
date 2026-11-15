package com.castlite.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The CastLite receiver API: a tiny HTTP + JSON protocol spoken between the phone
 * (client) and the CastLite TV app (server).
 *
 * ```
 * GET  http://<tv>:8766/api/info     → ReceiverInfo
 * GET  http://<tv>:8766/api/status   → ReceiverStatus
 * POST http://<tv>:8766/api/command  ← Command (JSON, "action" field)  → CommandResult
 * ```
 *
 * Example command: `{"action":"seek","positionMs":90000}`
 */
object ReceiverProtocol {
    /** Fixed port of the TV app's API, so "enter TV IP" needs only the IP. */
    const val PORT = 8766

    /** Bumped when features are added; fields added later always have defaults. */
    const val API_VERSION = 2

    /** DNS-SD service type the TV advertises over mDNS (NSD). */
    const val NSD_SERVICE_TYPE = "_castlite._tcp"

    const val PATH_INFO = "/api/info"
    const val PATH_STATUS = "/api/status"
    const val PATH_COMMAND = "/api/command"
    /** Plain-text log + device info, for "Send diagnostics" on the phone. */
    const val PATH_DIAGNOSTICS = "/api/diagnostics"

    /** Shared JSON configuration: tolerant of unknown fields so old/new apps interoperate. */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "action"
    }
}

/** What a queue entry is; tells the TV which player to use. */
@Serializable
enum class MediaType { VIDEO, IMAGE, AUDIO }

/** One file in the playlist sent to the TV. [url] points at the phone's media server. */
@Serializable
data class QueueItem(
    val url: String,
    val title: String,
    val mimeType: String,
    val type: MediaType,
    val durationMs: Long = 0,
    /** External subtitles (SubRip or WebVTT) served by the phone, if any. */
    val subtitleUrl: String? = null,
    val subtitleMimeType: String? = null,
)

/** What happens at the end of an item / the queue. */
@Serializable
enum class RepeatMode { OFF, ALL, ONE }

/** How photos change during a slideshow (CastLite TV app). */
@Serializable
enum class SlideTransition { CROSSFADE, SLIDE, ZOOM }

@Serializable
data class SlideshowOptions(
    val durationMs: Long = 5_000,
    val transition: SlideTransition = SlideTransition.CROSSFADE,
    /** Slow pan-and-zoom on each photo. */
    val kenBurns: Boolean = true,
    /** Play the queue's audio files as background music while the photos change. */
    val music: Boolean = false,
)

/** Commands the phone sends. The JSON "action" field selects the type. */
@Serializable
sealed interface Command {

    /** Replace the TV's queue and start playing [startIndex]. */
    @Serializable
    @SerialName("load")
    data class Load(
        val items: List<QueueItem>,
        val startIndex: Int = 0,
        val startPositionMs: Long = 0,
        /** How long each photo stays on screen (kept for older TV apps; see [slideshow]). */
        val slideDurationMs: Long = 5_000,
        val repeat: RepeatMode = RepeatMode.OFF,
        val slideshow: SlideshowOptions? = null,
    ) : Command

    @Serializable @SerialName("play") data object Play : Command
    @Serializable @SerialName("pause") data object Pause : Command
    @Serializable @SerialName("stop") data object Stop : Command
    @Serializable @SerialName("next") data object Next : Command
    @Serializable @SerialName("previous") data object Previous : Command

    @Serializable
    @SerialName("seek")
    data class Seek(val positionMs: Long) : Command

    @Serializable
    @SerialName("jump")
    data class JumpTo(val index: Int) : Command

    @Serializable
    @SerialName("volume")
    data class SetVolume(val percent: Int) : Command

    @Serializable
    @SerialName("repeat")
    data class SetRepeat(val mode: RepeatMode) : Command

    @Serializable
    @SerialName("subtitles")
    data class SetSubtitles(val enabled: Boolean) : Command
}

@Serializable
enum class ReceiverState { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

/** Snapshot of what the TV is doing; polled by the phone about once a second. */
@Serializable
data class ReceiverStatus(
    val state: ReceiverState = ReceiverState.IDLE,
    /** Index of the current queue item, or -1 when idle. */
    val index: Int = -1,
    val count: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0..100, or null if the TV can't report it. */
    val volumePercent: Int? = null,
    val title: String? = null,
    val type: MediaType? = null,
    /** Human-readable problem (codec unsupported, phone unreachable, ...). */
    val error: String? = null,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** null = the current item has no subtitles. */
    val subtitlesEnabled: Boolean? = null,
    /** True while the TV shows the phone's screen (mirroring). */
    val mirroring: Boolean = false,
)

/**
 * What the TV's hardware can decode, so the phone can warn before casting a file the
 * TV can't play. Codec names are MIME types, e.g. "video/hevc", "audio/ac3".
 */
@Serializable
data class Capabilities(
    val videoCodecs: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
    /** 10-bit HEVC (most HDR phone videos). */
    val hevcMain10: Boolean = false,
    /** Largest video height any decoder supports (e.g. 2160 for 4K). */
    val maxVideoHeight: Int = 1080,
)

/** Identity of a receiver; used to confirm a manually entered IP really is a CastLite TV. */
@Serializable
data class ReceiverInfo(
    val name: String,
    val model: String,
    val appVersion: String,
    val apiVersion: Int = ReceiverProtocol.API_VERSION,
    val app: String = "castlite-tv",
    val capabilities: Capabilities? = null,
    /** TCP port for screen mirroring, if supported. */
    val mirrorPort: Int? = null,
    /** TCP port for the reverse tunnel used when the phone is behind another router ([TunnelProtocol]). */
    val tunnelPort: Int? = null,
)

@Serializable
data class CommandResult(
    val ok: Boolean,
    val error: String? = null,
    val status: ReceiverStatus? = null,
)
