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

@UnstableApi
class SmbDataSource(private val context: Context) : BaseDataSource(false) {
    private var input: BufferedInputStream? = null
    private var openedUri: android.net.Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val file = SmbFile(dataSpec.uri.toString(), SmbRepository.contextForPlayback(context, dataSpec.uri.toString()))
        val stream = BufferedInputStream(SmbFileInputStream(file), READ_BUFFER_BYTES)
        try {
            var toSkip = dataSpec.position
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped > 0) toSkip -= skipped
                else if (stream.read() < 0) throw EOFException("SMBファイルの再生位置に移動できません")
                else toSkip--
            }
            input = stream
            openedUri = dataSpec.uri
            val fileLength = file.length()
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                else if (fileLength >= dataSpec.position) fileLength - dataSpec.position else C.LENGTH_UNSET.toLong()
            transferStarted(dataSpec)
            return remaining
        } catch (failure: Throwable) {
            stream.close()
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val allowed = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val count = input?.read(buffer, offset, allowed) ?: C.RESULT_END_OF_INPUT
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
            transferEnded()
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(context)
    }

    private companion object {
        const val READ_BUFFER_BYTES = 256 * 1024
    }
}
