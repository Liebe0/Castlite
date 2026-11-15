package com.castlite.phone.cast.dlna

import com.castlite.phone.cast.dlna.Xml.child
import com.castlite.phone.cast.dlna.Xml.childText
import com.castlite.phone.cast.dlna.Xml.children
import com.castlite.phone.cast.dlna.Xml.descendants
import java.net.URL

/** The parts of a UPnP device description that CastLite needs to control a renderer. */
data class RendererDescription(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val avTransportControlUrl: String,
    val renderingControlUrl: String?,
    val connectionManagerUrl: String? = null,
)

object DeviceDescriptionParser {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager"

    /**
     * Parses the XML found at an SSDP LOCATION. The renderer may be the root device or
     * an embedded one (common on TVs and AV receivers), so all devices are searched for
     * an AVTransport service. Returns null if there is none (not a renderer).
     */
    fun parse(xml: String, locationUrl: String): RendererDescription? {
        val root = Xml.parse(xml).documentElement
        val base = root.childText("URLBase") ?: locationUrl
        val devices = root.descendants("device")
        for (device in devices) {
            val services = device.child("serviceList")?.children("service").orEmpty()
            fun controlUrlOf(type: String) = services
                .firstOrNull { it.childText("serviceType")?.startsWith(type) == true }
                ?.childText("controlURL")
                ?.let { resolve(base, it) }
            val avTransport = controlUrlOf(AV_TRANSPORT) ?: continue
            return RendererDescription(
                udn = device.childText("UDN") ?: locationUrl,
                friendlyName = device.childText("friendlyName") ?: "TV",
                manufacturer = device.childText("manufacturer"),
                modelName = device.childText("modelName"),
                avTransportControlUrl = avTransport,
                renderingControlUrl = controlUrlOf(RENDERING_CONTROL),
                connectionManagerUrl = controlUrlOf(CONNECTION_MANAGER),
            )
        }
        return null
    }

    /** Resolves a possibly relative control URL ("/upnp/control/AVTransport1" or "AVT/ctrl"). */
    fun resolve(base: String, path: String): String = URL(URL(base), path.trim()).toString()
}
