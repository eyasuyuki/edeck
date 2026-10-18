package org.javaopen.edeck

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.io.EOFException

@UnstableApi
class SmbDataSource : BaseDataSource(false) {
    private var input: SmbFileInputStream? = null
    private var openedUri: android.net.Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val file = SmbFile(dataSpec.uri.toString(), SmbRepository.contextForPlayback())
        val stream = SmbFileInputStream(file)
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

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
