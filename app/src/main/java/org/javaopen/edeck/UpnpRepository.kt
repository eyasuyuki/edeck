package org.javaopen.edeck

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.Uri
import org.w3c.dom.Element
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URL
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import android.util.Log
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

data class UpnpMediaServer(val name: String, val descriptionUrl: URL, val contentDirectoryUrl: URL, val serviceType: String)

object UpnpRepository {
    fun discover(context: Context): List<UpnpMediaServer> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("edeck-upnp")?.apply {
            setReferenceCounted(false)
            // Android filters multicast packets unless an app explicitly holds this lock;
            // SSDP discovery otherwise appears to find no servers on some TV networks.
            acquire()
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val interfaceName = network?.let { connectivity.getLinkProperties(it)?.interfaceName }
        val targets = listOf(
            // Media servers vary in which SSDP search target they answer, so ask using
            // common device/service targets and deduplicate the returned locations.
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "ssdp:all"
        )
        val locations = linkedSetOf<String>()
        var responseCount = 0
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                socket.timeToLive = 2
                if (network != null) {
                    // Bind multicast traffic to Android's active LAN; the default route can
                    // otherwise send discovery over a VPN or a different network interface.
                    runCatching { network.bindSocket(socket) }
                        .onFailure { Log.w(TAG, "Could not bind SSDP socket to active network", it) }
                }
                if (interfaceName != null) {
                    runCatching { NetworkInterface.getByName(interfaceName) }
                        .getOrNull()?.let { networkInterface ->
                            runCatching { socket.networkInterface = networkInterface }
                                .onFailure { Log.w(TAG, "Could not set SSDP interface $interfaceName", it) }
                        }
                }
                Log.i(TAG, "Starting SSDP discovery on interface=${socket.networkInterface?.name ?: interfaceName ?: "default"}")
                targets.forEach { target ->
                    val request = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $target\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                    socket.send(DatagramPacket(
                        request, request.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT
                    ))
                }
                val deadline = System.currentTimeMillis() + DISCOVERY_WINDOW_MS
                // A socket timeout only ends one receive attempt. Continue until the shared
                // discovery window expires so slower NAS devices still have time to reply.
                while (System.currentTimeMillis() < deadline) {
                    val bytes = ByteArray(16 * 1024)
                    val packet = DatagramPacket(bytes, bytes.size)
                    try {
                        socket.receive(packet)
                        responseCount++
                        val headers = parseHeaders(packet.data, packet.offset, packet.length)
                        Log.d(TAG, "SSDP response from ${packet.address.hostAddress}, ST=${headers["st"]}, LOCATION=${headers["location"]}")
                        headers["location"]?.let(locations::add)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Continue until the discovery window closes.
                    }
                }
            }
            Log.i(TAG, "SSDP discovery received $responseCount response(s), ${locations.size} unique location(s)")
            val servers = locations.mapNotNull { location ->
                runCatching { readServer(URL(location)) }
                    .onFailure { Log.w(TAG, "Could not read UPnP device description at $location", it) }
                    .getOrNull()
            }.distinctBy { it.contentDirectoryUrl.toExternalForm() }
                .sortedBy { it.name.lowercase() }
            Log.i(TAG, "UPnP discovery found ${servers.size} ContentDirectory server(s)")
            return servers
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    internal fun parseHeaders(data: ByteArray, offset: Int, length: Int): Map<String, String> =
        String(data, offset, length, StandardCharsets.ISO_8859_1)
            .lineSequence().mapNotNull { line ->
                val split = line.indexOf(':')
                if (split > 0) line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
                else null
            }.toMap()

    fun listVideos(server: UpnpMediaServer): List<RemoteVideo> {
        val videos = mutableListOf<RemoteVideo>()
        // Some servers contain cycles or repeat container IDs. Track visited IDs and use
        // an explicit queue so traversal remains bounded and does not recurse on the stack.
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>().apply { add("0") }
        while (pending.isNotEmpty()) {
            val objectId = pending.removeFirst()
            if (!visited.add(objectId)) continue
            var start = 0
            var pageReturned = 0
            var totalMatches = 0
            do {
                // UPnP servers commonly cap a Browse response, so walk each container in
                // pages instead of assuming its first response is the whole directory.
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
                    // A ContentDirectory may mix audio, images, and generic objects with
                    // videos; exclude known non-video classes before considering resources.
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

    fun browseDirectory(server: UpnpMediaServer, objectId: String): List<RemoteBrowserItem> {
        // The browser requests only the selected container's direct children. Recursive
        // expansion here would make navigating one folder pay the cost of scanning all of
        // the server again, defeating the point of directory browsing.
        val entries = mutableListOf<RemoteBrowserItem>()
        var start = 0
        var pageReturned: Int
        var totalMatches: Int
        do {
            val page = browse(server, objectId, start)
            val didl = page.didl
            val containers = didl.getElementsByTagNameNS("*", "container")
            for (index in 0 until containers.length) {
                val container = containers.item(index) as? Element ?: continue
                val id = container.getAttribute("id")
                if (id.isNotBlank()) entries += RemoteBrowserItem(
                    container.firstText("title").ifBlank { "フォルダー" }, directoryId = id
                )
            }
            val items = didl.getElementsByTagNameNS("*", "item")
            for (index in 0 until items.length) {
                val item = items.item(index) as? Element ?: continue
                val itemClass = item.firstText("class")
                if (itemClass.isNotEmpty() && !itemClass.startsWith("object.item.videoItem")) continue
                val video = item.toRemoteVideo() ?: continue
                entries += RemoteBrowserItem(video.title, video = video)
            }
            pageReturned = page.numberReturned.takeIf { it > 0 } ?: (containers.length + items.length)
            totalMatches = page.totalMatches.takeIf { it > 0 } ?: (start + pageReturned)
            start += pageReturned
        } while (pageReturned > 0 && start < totalMatches && start < MAX_RESULTS_PER_CONTAINER)
        return entries.distinctBy { it.directoryId ?: it.video?.uri?.toString() }
            .sortedWith(compareBy<RemoteBrowserItem> { it.directoryId == null }.thenBy { it.title.lowercase() })
    }

    private fun Element.toRemoteVideo(): RemoteVideo? {
        val title = firstText("title").ifBlank { "動画" }
        val date = firstText("date").toTimestamp()
        val resources = getElementsByTagNameNS("*", "res")
        for (index in 0 until resources.length) {
            val resource = resources.item(index) as? Element ?: continue
            val address = resource.textContent.trim()
            val mime = resource.getAttribute("protocolInfo").split(':').getOrNull(2).orEmpty().lowercase()
            // Prefer protocol metadata, but accept extension-only servers that leave MIME
            // fields vague; only HTTP(S) resources can be opened by the configured player.
            if ((mime.startsWith("video/") || address.isVideoUrl()) &&
                (address.startsWith("http://", true) || address.startsWith("https://", true))) {
                return RemoteVideo(title, Uri.parse(address), date)
            }
        }
        return null
    }

    private fun readServer(descriptionUrl: URL): UpnpMediaServer {
        val document = httpGet(descriptionUrl)
        // Device descriptions may specify a base URL for relative control paths; resolving
        // against it avoids constructing invalid endpoints from the description URL.
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
        // Object IDs and service types come from the remote server; XML-escape the ID so
        // names containing punctuation cannot break the SOAP envelope.
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
            // ContentDirectory is a SOAP action: both the XML envelope and this header are
            // required by common DLNA servers before they return DIDL-Lite metadata.
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

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            // Device metadata is network input. Disable DTD/external entities so parsing a
            // malicious description cannot make the TV fetch local files or arbitrary URLs.
            setOptionalFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setOptionalFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setOptionalFeature("http://xml.org/sax/features/external-general-entities", false)
            setOptionalFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder().apply {
            // Some Android XML parsers reject the security feature flags above. Never
            // allow their fallback parser to fetch an external DTD or entity over the network.
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return builder.parse(ByteArrayInputStream(bytes))
    }

    private fun DocumentBuilderFactory.setOptionalFeature(name: String, value: Boolean) {
        // Android's XML providers support different feature subsets; the entity resolver
        // below remains the fallback protection when a hardening flag is unavailable.
        runCatching { setFeature(name, value) }
    }

    private fun Element.firstText(localName: String): String =
        getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim().orEmpty()

    private fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    // Missing or non-ISO dates sort as the oldest entries instead of failing the entire
    // server scan because one item's metadata is incomplete.
    private fun String.toTimestamp(): Long = runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrDefault(0L)

    private fun String.isVideoUrl(): Boolean = substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        .lowercase() in VIDEO_EXTENSIONS

    private const val TAG = "EDeck-UPnP"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val RECEIVE_TIMEOUT_MS = 350
    private const val DISCOVERY_WINDOW_MS = 6_000L
    private const val HTTP_TIMEOUT_MS = 12_000
    private const val PAGE_SIZE = 200
    private const val MAX_CONTAINERS = 10_000
    private const val MAX_RESULTS_PER_CONTAINER = 100_000
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "mpg", "mpeg")
}
