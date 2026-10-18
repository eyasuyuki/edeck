package org.javaopen.edeck

import android.content.Context
import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

object SmbRepository {
    @Volatile
    private var activeContext: CIFSContext? = null

    fun scanVideos(address: String, domain: String, username: String, password: String): List<RemoteVideo> {
        val normalized = address.trim().let { if (it.startsWith("smb://", true)) it else "smb://$it" }
            .let { if (it.endsWith('/')) it else "$it/" }
        val cifs = createContext(domain, username, password)
        activeContext = cifs

        val root = SmbFile(normalized, cifs)
        require(root.exists()) { "SMB共有に接続できません" }
        val videos = mutableListOf<RemoteVideo>()
        val pending = ArrayDeque<SmbFile>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty()) {
            check(++visited <= MAX_ENTRIES) { "検索対象が多すぎます" }
            pending.removeFirst().listFiles().forEach { file ->
                if (file.isDirectory) pending.add(file)
                else if (file.isFile && file.name.isSupportedVideo()) {
                    videos += RemoteVideo(file.name, Uri.parse(file.url.toString()), file.lastModified())
                }
            }
        }
        return videos.sortedByDescending(RemoteVideo::lastModified)
    }

    fun contextForPlayback(context: Context, address: String): CIFSContext {
        val saved = SmbCredentialsStore.credentialsForResource(context, address)
        return if (saved != null) createContext(saved.domain, saved.username, saved.password)
        else activeContext ?: error("SMB credentials are unavailable. Reconnect to the share.")
    }

    private fun createContext(domain: String, username: String, password: String): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "30000")
            setProperty("jcifs.smb.client.connTimeout", "15000")
        }
        val authenticator = NtlmPasswordAuthenticator(
            domain.ifBlank { null }, username.ifBlank { null }, password
        )
        return BaseContext(PropertyConfiguration(properties)).withCredentials(authenticator)
    }

    private fun String.isSupportedVideo(): Boolean = substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    private const val MAX_ENTRIES = 20_000
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "mpg", "mpeg")
}
