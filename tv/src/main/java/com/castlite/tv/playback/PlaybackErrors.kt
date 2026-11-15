package com.castlite.tv.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException

/** Turns ExoPlayer failures into messages a person can act on. */
@OptIn(UnstableApi::class) // rendererFormat tells us which codec failed
object PlaybackErrors {

    fun describe(e: PlaybackException): String {
        val format = (e as? ExoPlaybackException)
            ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
            ?.rendererFormat
        val codec = format?.let { f ->
            listOfNotNull(
                f.sampleMimeType ?: f.codecs,
                if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
            ).joinToString(" ")
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            -> "This TV can't decode this file" + (codec?.let { " ($it)" } ?: "") +
                ". Try a different file, or convert it to H.264 / AAC MP4."

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> "This file type isn't supported. MP4, MKV, WebM, MP3, AAC and FLAC work best."

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            -> "The file looks damaged or incomplete."

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "Can't reach the phone. Check that both are on the same Wi-Fi and CastLite is still open on the phone."

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            -> "The phone stopped sharing this file. Pick it again on the phone."

            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_TIMEOUT,
            -> "Playback timed out. The Wi-Fi may be too slow for this file."

            else -> "Playback failed: ${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}."
        }
    }
}
