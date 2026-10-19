package org.javaopen.edeck

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream

@UnstableApi
class SmbDataSource(private val context: Context) : BaseDataSource(false) {
    private var input: RetryingInputStream? = null
    private var openedUri: android.net.Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var openStreamAt: ((Long) -> InputStream)? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        // ExoPlayer may reopen a resource at a byte offset after buffering or seeking, so
        // each open resolves credentials from the actual file URI rather than the list row.
        val cifsContext = SmbRepository.contextForPlayback(context, uri.toString())
        val file = SmbFile(uri.toString(), cifsContext)
        try {
            val fileLength = file.length()
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                else if (fileLength >= dataSpec.position) fileLength - dataSpec.position else C.LENGTH_UNSET.toLong()
            openStreamAt = { position ->
                val reopenedFile = SmbFile(uri.toString(), cifsContext)
                BufferedInputStream(SmbFileInputStream(reopenedFile), READ_BUFFER_BYTES).also { stream ->
                    // jcifs streams are sequential; skipping here emulates the random-access
                    // ranges ExoPlayer expects when it seeks or retries a media read.
                    skipFully(stream, position)
                }
            }
            input = RetryingInputStream(
                initialStream = openStreamAt!!.invoke(dataSpec.position),
                initialPosition = dataSpec.position,
                reopenAt = { position -> openStreamAt!!.invoke(position) }
            )
            openedUri = uri
            transferStarted(dataSpec)
            return remaining
        } catch (failure: Throwable) {
            input?.close()
            input = null
            openStreamAt = null
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val allowed = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        // A known content length lets us distinguish a normal response EOF from a dropped
        // SMB connection that ended before all requested bytes arrived.
        val count = input?.read(
            buffer, offset, allowed,
            retryOnUnexpectedEof = remaining != C.LENGTH_UNSET.toLong()
        ) ?: C.RESULT_END_OF_INPUT
        if (count == -1) {
            if (remaining == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
            throw EOFException("SMBファイルの読み込み中に接続が終了しました")
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri() = openedUri

    override fun close() {
        openedUri = null
        remaining = C.LENGTH_UNSET.toLong()
        try {
            input?.close()
        } finally {
            input = null
            openStreamAt = null
            transferEnded()
        }
    }

    private fun skipFully(stream: InputStream, position: Long) {
        // InputStream.skip() is allowed to skip fewer bytes than requested (including 0),
        // so keep consuming until the exact resume offset is reached or EOF proves it invalid.
        var remainingToSkip = position
        while (remainingToSkip > 0) {
            val skipped = stream.skip(remainingToSkip)
            if (skipped > 0) remainingToSkip -= skipped
            else if (stream.read() < 0) throw EOFException("SMBファイルの再生位置に移動できません")
            else remainingToSkip--
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(context)
    }

    private companion object {
        const val READ_BUFFER_BYTES = 256 * 1024
    }
}
