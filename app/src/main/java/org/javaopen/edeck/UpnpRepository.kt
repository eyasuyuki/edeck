package org.javaopen.edeck

import android.content.Context
import android.net.wifi.WifiManager
import android.net.Uri
import org.w3c.dom.Element
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

data class UpnpMediaServer(val name: String, val descriptionUrl: URL, val contentDirectoryUrl: URL, val serviceType: String)

object UpnpRepository {
    fun discover(context: Context): List<UpnpMediaServer> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("edeck-upnp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val request = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: ssdp:all\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
            val locations = linkedSetOf<String>()
            MulticastSocket().use { socket ->
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                socket.timeToLive = 2
                socket.send(DatagramPacket(
                    request, request.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT
                ))
                val deadline = System.currentTimeMillis() + DISCOVERY_WINDOW_MS
                while (System.currentTimeMillis() < deadline) {
                    val bytes = ByteArray(16 * 1024)
                    val packet = DatagramPacket(bytes, bytes.size)
                    try {
                        socket.receive(packet)
                        val headers = String(packet.data, packet.offset, packet.length, StandardCharsets.ISO_8859_1)
                            .lineSequence().mapNotNull { line ->
                                val split = line.indexOf(':')
                                if (split > 0) line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
                                else null
                            }.toMap()
                        headers["location"]?.let(locations::add)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Continue until the discovery window closes.
                    }
                }
            }
            return locations.mapNotNull { location -> runCatching { readServer(URL(location)) }.getOrNull() }
                .distinctBy { it.contentDirectoryUrl.toExternalForm() }
                .sortedBy { it.name.lowercase() }
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    fun listVideos(server: UpnpMediaServer): List<RemoteVideo> {
        val videos = mutableListOf<RemoteVideo>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>().apply { add("0") }
        while (pending.isNotEmpty()) {
            val objectId = pending.removeFirst()
            if (!visited.add(objectId)) continue
            var start = 0
            var pageReturned = 0
            var totalMatches = 0
            do {
                val page = browse(server, objectId, start)
                val didl = page.didl
                val containers = didl.getElementsByTagNameNS("*", "container")
                for (index in 0 until containers.length) {
                    val container = containers.item(index) as? Element ?: continue
                    container.getAttribute("id").takeIf { it.isNotBlank() }?.let(pending::add)
                }
                val items = didl.getElementsByTagNameNS("*", "item")
                for (index in 0 until items.length) {
                    val item = items.item(index) as? Element ?: continue
                    val itemClass = item.firstText("class")
                    if (itemClass.isNotEmpty() && !itemClass.startsWith("object.item.videoItem")) continue
                    val title = item.firstText("title").ifBlank { "動画" }
                    val date = item.firstText("date").toTimestamp()
                    val resources = item.getElementsByTagNameNS("*", "res")
                    for (resourceIndex in 0 until resources.length) {
                        val resource = resources.item(resourceIndex) as? Element ?: continue
                        val address = resource.textContent.trim()
                        val mime = resource.getAttribute("protocolInfo").split(':').getOrNull(2).orEmpty().lowercase()
                        if ((mime.startsWith("video/") || address.isVideoUrl()) &&
                            (address.startsWith("http://", true) || address.startsWith("https://", true))) {
                            videos += RemoteVideo(title, Uri.parse(address), date)
                            break
                        }
                    }
                }
                pageReturned = page.numberReturned.takeIf { it > 0 } ?: (containers.length + items.length)
                totalMatches = page.totalMatches.takeIf { it > 0 } ?: (start + pageReturned)
                start += pageReturned
            } while (pageReturned > 0 && start < totalMatches && start < MAX_RESULTS_PER_CONTAINER)
            check(visited.size <= MAX_CONTAINERS) { "UPnPフォルダーが多すぎます" }
        }
        return videos.distinctBy { it.uri.toString() }.sortedByDescending(RemoteVideo::lastModified)
    }

    private fun readServer(descriptionUrl: URL): UpnpMediaServer {
        val document = httpGet(descriptionUrl)
        val baseAddress = document.getElementsByTagNameNS("*", "URLBase").item(0)?.textContent?.trim()
            ?.takeIf(String::isNotEmpty)?.let(::URL) ?: descriptionUrl
        val device = document.getElementsByTagNameNS("*", "device").item(0) as? Element
            ?: error("UPnPデバイス情報がありません")
        val name = device.firstText("friendlyName").ifBlank { descriptionUrl.host }
        val services = device.getElementsByTagNameNS("*", "service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? Element ?: continue
            val type = service.firstText("serviceType")
            if (type.startsWith("urn:schemas-upnp-org:service:ContentDirectory:")) {
                val control = service.firstText("controlURL")
                return UpnpMediaServer(name, descriptionUrl, URL(baseAddress, control), type)
            }
        }
        error("ContentDirectoryに対応していません")
    }

    private data class BrowsePage(val didl: Document, val numberReturned: Int, val totalMatches: Int)

    private fun browse(server: UpnpMediaServer, objectId: String, start: Int): BrowsePage {
        val escapedId = objectId.xmlEscape()
        val soap = """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body><u:Browse xmlns:u="${server.serviceType}">
                <ObjectID>$escapedId</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>
                <StartingIndex>$start</StartingIndex><RequestedCount>$PAGE_SIZE</RequestedCount><SortCriteria></SortCriteria>
              </u:Browse></s:Body>
            </s:Envelope>""".trimIndent()
        val connection = (server.contentDirectoryUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"${server.serviceType}#Browse\"")
        }
        try {
            connection.outputStream.use { it.write(soap.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            check(code in 200..299) { "UPnP応答 HTTP $code" }
            val response = connection.inputStream.use { parseXml(it.readBytes()) }
            val didlText = response.getElementsByTagNameNS("*", "Result").item(0)?.textContent
                ?: error("UPnP Browse応答にResultがありません")
            return BrowsePage(
                didl = parseXml(didlText.toByteArray(StandardCharsets.UTF_8)),
                numberReturned = response.getElementsByTagNameNS("*", "NumberReturned").item(0)?.textContent?.toIntOrNull() ?: 0,
                totalMatches = response.getElementsByTagNameNS("*", "TotalMatches").item(0)?.textContent?.toIntOrNull() ?: 0,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: URL) = url.openConnection().let { connection ->
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.getInputStream().use { parseXml(it.readBytes()) }
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun Element.firstText(localName: String): String =
        getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim().orEmpty()

    private fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun String.toTimestamp(): Long = runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrDefault(0L)

    private fun String.isVideoUrl(): Boolean = substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        .lowercase() in VIDEO_EXTENSIONS

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val RECEIVE_TIMEOUT_MS = 350
    private const val DISCOVERY_WINDOW_MS = 4_000L
    private const val HTTP_TIMEOUT_MS = 12_000
    private const val PAGE_SIZE = 200
    private const val MAX_CONTAINERS = 10_000
    private const val MAX_RESULTS_PER_CONTAINER = 100_000
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "mpg", "mpeg")
}
