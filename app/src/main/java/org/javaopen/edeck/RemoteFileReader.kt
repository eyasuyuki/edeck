package org.javaopen.edeck

import java.net.HttpURLConnection
import java.net.URL

/** Small HTTPS text-file reader. Keep network I/O off the main thread. */
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
            connection.disconnect()
        }
    }
}
