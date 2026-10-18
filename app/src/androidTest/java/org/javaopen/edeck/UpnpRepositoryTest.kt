package org.javaopen.edeck

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class UpnpRepositoryTest {
    @Test
    fun parsesSsdpHeadersWithoutDependingOnHeaderCase() {
        val message = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "LOCATION: http://192.0.2.20:1400/device.xml\r\n\r\n"

        val headers = UpnpRepository.parseHeaders(message.toByteArray(StandardCharsets.ISO_8859_1), 0, message.length)

        assertEquals("urn:schemas-upnp-org:device:MediaServer:1", headers["st"])
        assertEquals("http://192.0.2.20:1400/device.xml", headers["location"])
    }

    @Test
    fun browsesUpnpServerAndExtractsVideoResources() {
        ServerSocket(0).use { server ->
            val failure = arrayOfNulls<Throwable>(1)
            val worker = thread {
                try {
                    server.accept().use { client ->
                        client.soTimeout = 5_000
                        val input = client.getInputStream()
                        readHttpRequestBody(input)
                        val body = soapBrowseResponse(server.localPort).toByteArray(StandardCharsets.UTF_8)
                        val headers = ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/xml; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                        client.getOutputStream().apply {
                            write(headers)
                            write(body)
                            flush()
                        }
                    }
                } catch (error: Throwable) {
                    failure[0] = error
                }
            }

            val serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1"
            val serverInfo = UpnpMediaServer(
                "Mock media server",
                URL("http://127.0.0.1:${server.localPort}/description.xml"),
                URL("http://127.0.0.1:${server.localPort}/control"),
                serviceType
            )
            val videos = UpnpRepository.listVideos(serverInfo)
            worker.join(5_000)

            assertTrue("Mock UPnP response server failed: ${failure[0]}", failure[0] == null)
            assertEquals(1, videos.size)
            assertEquals("Example movie", videos.single().title)
            assertEquals("http://127.0.0.1:${server.localPort}/media/movie.mp4", videos.single().uri.toString())
            assertEquals(1_700_000_000_000L, videos.single().lastModified)
        }
    }

    private fun readHttpRequestBody(input: java.io.InputStream) {
        val headers = StringBuilder()
        var previous = 0
        while (headers.length < 16_384) {
            val next = input.read()
            if (next == -1) break
            headers.append(next.toChar())
            if (previous == '\r'.code && next == '\n'.code && headers.endsWith("\r\n\r\n")) break
            previous = next
        }
        // The request body is chunked by HttpURLConnection on some Android versions.
        // Reading through the closing SOAP tag works for both chunked and fixed-length bodies.
        val closingTag = "</s:Envelope>"
        var matched = 0
        while (matched < closingTag.length) {
            val next = input.read()
            if (next == -1) break
            if (next.toChar() == closingTag[matched]) matched++
            else matched = if (next.toChar() == closingTag[0]) 1 else 0
        }
        assertEquals("UPnP Browse request body ended early", closingTag.length, matched)
    }

    private fun soapBrowseResponse(port: Int): String {
        val didlComplete = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="movie-1"><dc:title>Example movie</dc:title><dc:date>2023-11-14T22:13:20Z</dc:date><upnp:class>object.item.videoItem.movie</upnp:class><res protocolInfo="http-get:*:video/mp4:*">http://127.0.0.1:$port/media/movie.mp4</res></item></DIDL-Lite>"""
        val escapedDidl = didlComplete
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>$escapedDidl</Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"""
    }
}
