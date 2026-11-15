package com.castlite.phone.cast.dlna

import com.castlite.phone.media.MediaKind
import com.castlite.phone.media.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaPrimitivesTest {

    // ---- UpnpTime ----
    @Test fun formatsSeekTarget() {
        assertEquals("00:01:23", UpnpTime.format(83_500))
        assertEquals("01:02:03", UpnpTime.format(3_723_000))
        assertEquals("00:00:00", UpnpTime.format(-5))
    }

    @Test fun parsesPositions() {
        assertEquals(83_456, UpnpTime.parse("0:01:23.456"))
        assertEquals(83_500, UpnpTime.parse("00:01:23.5"))
        assertEquals(3_723_000, UpnpTime.parse("1:02:03"))
        assertEquals(0, UpnpTime.parse("NOT_IMPLEMENTED"))
        assertEquals(0, UpnpTime.parse(null))
    }

    @Test fun formatsDidlDuration() = assertEquals("0:01:23.500", UpnpTime.formatDuration(83_500))

    // ---- SSDP ----
    @Test fun parsesSearchReply() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.50:1849/description.xml\r\n" +
            "SERVER: WebOS/4.1.0 UPnP/1.0\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val r = Ssdp.parse(reply)!!
        assertEquals("http://192.168.1.50:1849/description.xml", r.location)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", r.searchTarget)
        assertTrue(r.server!!.contains("WebOS"))
    }

    @Test fun headerNamesAreCaseInsensitive() =
        assertNotNull(Ssdp.parse("HTTP/1.1 200 OK\nLocation: http://10.0.0.2/d.xml\n\n"))

    @Test fun ignoresByebyeAndJunk() {
        assertNull(Ssdp.parse("NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nLOCATION: http://x/d.xml\r\n\r\n"))
        assertNull(Ssdp.parse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n"))
        assertNull(Ssdp.parse("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
    }

    @Test fun searchMessageIsWellFormed() {
        val m = Ssdp.searchMessage(Ssdp.MEDIA_RENDERER)
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(m.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(m.endsWith("\r\n\r\n"))
    }

    // ---- Device description ----
    @Test fun parsesLgStyleDescriptionWithEmbeddedRenderer() {
        val d = DeviceDescriptionParser.parse(LG_DESCRIPTION, "http://192.168.1.50:1849/description.xml")!!
        assertEquals("[LG] webOS TV OLED55C1", d.friendlyName)
        assertEquals("LG Electronics", d.manufacturer)
        assertEquals("uuid:1234-lg", d.udn)
        assertEquals("http://192.168.1.50:1849/AVTransport/control", d.avTransportControlUrl)
        assertEquals("http://192.168.1.50:1849/RenderingControl/control", d.renderingControlUrl)
    }

    @Test fun honoursUrlBaseAndRelativePaths() {
        val xml = """<root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://10.0.0.9:8080/base/</URLBase>
            <device><friendlyName>Kodi</friendlyName><UDN>uuid:k</UDN><serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>avt/ctrl</controlURL>
            </service></serviceList></device></root>"""
        val d = DeviceDescriptionParser.parse(xml, "http://10.0.0.9:1234/x.xml")!!
        assertEquals("http://10.0.0.9:8080/base/avt/ctrl", d.avTransportControlUrl)
        assertNull(d.renderingControlUrl)
    }

    @Test fun mediaServersAreNotRenderers() {
        val xml = """<root><device><friendlyName>NAS</friendlyName><serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/cd</controlURL>
            </service></serviceList></device></root>"""
        assertNull(DeviceDescriptionParser.parse(xml, "http://10.0.0.3/d.xml"))
    }

    // ---- SOAP ----
    @Test fun envelopeEscapesArguments() {
        val env = Soap.envelope("urn:x:AVTransport:1", "SetAVTransportURI", listOf("CurrentURIMetaData" to "<DIDL-Lite>&</DIDL-Lite>"))
        assertTrue(env.contains("<u:SetAVTransportURI xmlns:u=\"urn:x:AVTransport:1\">"))
        assertTrue(env.contains("<CurrentURIMetaData>&lt;DIDL-Lite&gt;&amp;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"))
    }

    @Test fun parsesActionResponse() {
        val xml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track><TrackDuration>0:10:00</TrackDuration><RelTime>0:01:02</RelTime>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>"""
        val out = Soap.parseResponse(xml, "GetPositionInfo")
        assertEquals("0:01:02", out["RelTime"])
        assertEquals("0:10:00", out["TrackDuration"])
    }

    @Test fun parsesUpnpFault() {
        val xml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode>
            <errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        val e = Soap.parseFault(xml)!!
        assertEquals(714, e.code)
        assertEquals("Illegal MIME-type", e.description)
    }

    @Test fun rejectsDoctypes() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><root>&x;</root>"""
        assertNull(Soap.parseFault(evil))
    }

    // ---- DIDL-Lite ----
    @Test fun didlCarriesMimeSizeAndDuration() {
        val didl = Didl.build("Holiday & <Fun>", "http://192.168.1.2:8765/media/abc/h.mp4", "video/mp4", MediaKind.VIDEO, 1234, 83_500)
        assertTrue(didl.contains("<dc:title>Holiday &amp; &lt;Fun&gt;</dc:title>"))
        assertTrue(didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01;"))
        assertTrue(didl.contains("size=\"1234\""))
        assertTrue(didl.contains("duration=\"0:01:23.500\""))
        assertTrue(didl.contains(">http://192.168.1.2:8765/media/abc/h.mp4</res>"))
        // Must itself be well-formed XML.
        Xml.parse(didl)
    }

    @Test fun didlForPhotos() {
        val didl = Didl.build("p", "http://x/p.jpg", "image/jpeg", MediaKind.IMAGE, 10, 0)
        assertTrue(didl.contains("object.item.imageItem.photo"))
        assertTrue(!didl.contains("duration="))
    }

    // ---- MIME normalization ----
    @Test fun normalizesMimeTypes() {
        assertEquals("audio/mpeg", MimeTypes.normalize("audio/mp3", "a.mp3"))
        assertEquals("audio/mp4", MimeTypes.normalize("audio/x-m4a", "a.m4a"))
        assertEquals("video/x-matroska", MimeTypes.normalize("application/octet-stream", "movie.MKV"))
        assertEquals("image/jpeg", MimeTypes.normalize("image/jpg; charset=binary", "x.jpg"))
        assertEquals("video/mp4", MimeTypes.normalize("video/mp4", "x"))
        assertEquals("video/mp4", MimeTypes.normalize(null, "clip.mp4"))
    }

    companion object {
        val LG_DESCRIPTION = """<?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
                <friendlyName>LG TV root</friendlyName>
                <UDN>uuid:root</UDN>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>[LG] webOS TV OLED55C1</friendlyName>
                    <manufacturer>LG Electronics</manufacturer>
                    <modelName>OLED55C1</modelName>
                    <UDN>uuid:1234-lg</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                        <controlURL>/RenderingControl/control</controlURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                        <controlURL>/ConnectionManager/control</controlURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/AVTransport/control</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>"""
    }
}
