package org.javaopen.edeck

import java.net.HttpURLConnection
import java.net.URL

/**
 * Small HTTP(S) text-file reader. Callers run it off the main thread because requests can
 * wait on a remote server; the byte cap keeps a URL that is not actually a small text file
 * from consuming unbounded memory while the response is buffered.
 */
object RemoteFileReader {
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun readText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES) {
                        "ファイルが大きすぎます (上限 2 MiB)"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } finally {
            // Always release the socket even when status validation or the size cap fails;
            // callers may retry the same URL immediately after handling the error.
            connection.disconnect()
        }
    }
}
