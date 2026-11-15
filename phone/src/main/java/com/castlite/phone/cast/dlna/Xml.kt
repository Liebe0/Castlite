package com.castlite.phone.cast.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/** Small namespace-agnostic DOM helpers (UPnP devices are sloppy with namespaces). */
internal object Xml {

    fun parse(text: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Refuse DTDs: blocks XXE / entity-expansion tricks from hostile devices.
            trySet("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySet("http://xml.org/sax/features/external-general-entities", false)
            trySet("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(text.trim().byteInputStream(Charsets.UTF_8))
    }

    private fun DocumentBuilderFactory.trySet(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (e: Exception) {
            // Android's parser doesn't support every feature flag; it doesn't load external entities anyway.
        }
    }

    fun Node.localNameOrName(): String = (localName ?: nodeName).substringAfter(':')

    fun Element.children(name: String): List<Element> = buildList {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.localNameOrName() == name) add(n)
        }
    }

    fun Element.child(name: String): Element? = children(name).firstOrNull()

    fun Element.childText(name: String): String? = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /** All descendants named [name], depth-first. */
    fun Element.descendants(name: String): List<Element> = buildList {
        fun walk(e: Element) {
            val nodes = e.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n is Element) {
                    if (n.localNameOrName() == name) add(n)
                    walk(n)
                }
            }
        }
        walk(this@descendants)
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
