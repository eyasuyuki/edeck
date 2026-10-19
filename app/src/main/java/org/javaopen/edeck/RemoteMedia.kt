package org.javaopen.edeck

import android.net.Uri

// The URI is the persistent identity used by cache and playback preferences; titles are
// not unique on many servers and therefore cannot safely key progress state.
data class RemoteVideo(
    val title: String,
    val uri: Uri,
    val lastModified: Long,
)

data class RemoteBrowserItem(
    val title: String,
    val directoryId: String? = null,
    val video: RemoteVideo? = null,
)
