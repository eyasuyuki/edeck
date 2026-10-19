package org.javaopen.edeck

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class RetryingInputStreamTest {
    @Test
    fun reconnectsFromFirstByteNotYetDelivered() {
        // In-memory streams make the failure deterministic while checking the key invariant:
        // bytes already returned to ExoPlayer must never be repeated after reconnect.
        var firstRead = true
        val resumeOffsets = mutableListOf<Long>()
        val initialStream = object : InputStream() {
            private var sentPrefix = false

            override fun read(): Int = error("Bulk reads are expected")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!sentPrefix) {
                    sentPrefix = true
                    "ab".toByteArray().copyInto(buffer, offset)
                    return 2
                }
                if (firstRead) {
                    firstRead = false
                    throw IOException("Temporary network interruption")
                }
                return -1
            }
        }
        val source = RetryingInputStream(
            initialStream = initialStream,
            initialPosition = 0,
            reopenAt = { position ->
                resumeOffsets += position
                ByteArrayInputStream("cdef".toByteArray())
            },
            retryDelayMs = 0
        )
        val result = ByteArray(6)

        try {
            val firstCount = source.read(result, 0, result.size)
            val secondCount = source.read(result, firstCount, result.size - firstCount)

            assertEquals(2, firstCount)
            assertEquals(4, secondCount)
            assertEquals("abcdef", result.toString(Charsets.UTF_8))
            assertEquals(listOf(2L), resumeOffsets)
        } finally {
            source.close()
        }
    }

    @Test
    fun retriesUnexpectedEofWhenTheResourceLengthIsKnown() {
        // A short read looks like success until the caller tells us the file length is known;
        // this case protects SMB playback from silently treating a dropped connection as EOF.
        var reconnectCount = 0
        val source = RetryingInputStream(
            initialStream = ByteArrayInputStream(byteArrayOf()),
            initialPosition = 0,
            reopenAt = {
                reconnectCount++
                ByteArrayInputStream(byteArrayOf(42))
            },
            retryDelayMs = 0
        )
        val result = ByteArray(1)

        try {
            assertEquals(1, source.read(result, 0, 1, retryOnUnexpectedEof = true))
            assertEquals(42.toByte(), result[0])
            assertEquals(1, reconnectCount)
        } finally {
            source.close()
        }
    }

    @Test
    fun stopsAfterTheConfiguredRetryLimit() {
        var reconnectCount = 0
        val source = RetryingInputStream(
            initialStream = failingStream(),
            initialPosition = 0,
            reopenAt = {
                reconnectCount++
                failingStream()
            },
            maxRetries = 2,
            retryDelayMs = 0
        )

        try {
            assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
            assertEquals(2, reconnectCount)
        } finally {
            source.close()
        }
    }

    private fun failingStream() = object : InputStream() {
        override fun read(): Int = throw IOException("Still disconnected")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Still disconnected")
    }
}
