package com.castlite.phone.cast.dlna

import com.castlite.phone.media.MediaKind
import com.castlite.phone.server.Dlna

/**
 * DIDL-Lite metadata sent with SetAVTransportURI. Many renderers (LG webOS included)
 * read the MIME type from `protocolInfo` and refuse the URI if it's missing or wrong.
 */
object Didl {

    fun build(
        title: String,
        url: String,
        mimeType: String,
        kind: MediaKind,
        sizeBytes: Long,
        durationMs: Long,
    ): String {
        val upnpClass = when (kind) {
            MediaKind.VIDEO -> "object.item.videoItem"
            MediaKind.IMAGE -> "object.item.imageItem.photo"
            MediaKind.AUDIO -> "object.item.audioItem.musicTrack"
            MediaKind.OTHER -> "object.item"
        }
        val protocolInfo = "http-get:*:$mimeType:${Dlna.contentFeatures(kind, seekable = sizeBytes >= 0)}"
        val attrs = buildString {
            append(" protocolInfo=\"").append(Xml.escape(protocolInfo)).append('"')
            if (sizeBytes >= 0) append(" size=\"").append(sizeBytes).append('"')
            if (durationMs > 0 && kind != MediaKind.IMAGE) {
                append(" duration=\"").append(UpnpTime.formatDuration(durationMs)).append('"')
            }
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${Xml.escape(title)}</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res$attrs>${Xml.escape(url)}</res>" +
            "</item></DIDL-Lite>"
    }
}
