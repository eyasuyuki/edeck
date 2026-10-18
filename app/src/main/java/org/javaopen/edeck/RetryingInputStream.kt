package org.javaopen.edeck

import java.io.IOException
import java.io.InputStream

/** Reopens a failed stream at the first byte not yet delivered to the caller. */
internal class RetryingInputStream(
    initialStream: InputStream,
    initialPosition: Long,
    private val reopenAt: (Long) -> InputStream,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) : AutoCloseable {
    private var stream: InputStream? = initialStream
    private var position = initialPosition

    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int, retryOnUnexpectedEof: Boolean = false): Int {
        var retries = 0
        while (true) {
            try {
                if (stream == null) {
                    if (retryDelayMs > 0) Thread.sleep(retryDelayMs)
                    stream = reopenAt(position)
                }
                val count = stream!!.read(buffer, offset, length)
                if (count > 0) position += count
                if (count >= 0 || !retryOnUnexpectedEof) return count
                throw IOException("Unexpected end of SMB stream")
            } catch (error: IOException) {
                if (retries >= maxRetries) throw error
                retries++
                discardCurrentStream()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while reconnecting to the SMB share", interrupted)
            }
        }
    }

    private fun discardCurrentStream() {
        try {
            stream?.close()
        } catch (_: IOException) {
            // The failed stream is discarded regardless of whether close succeeds.
        }
        stream = null
    }

    @Throws(IOException::class)
    override fun close() {
        try {
            stream?.close()
        } finally {
            stream = null
        }
    }

    companion object {
        private const val DEFAULT_MAX_RETRIES = 2
        private const val DEFAULT_RETRY_DELAY_MS = 400L
    }
}
