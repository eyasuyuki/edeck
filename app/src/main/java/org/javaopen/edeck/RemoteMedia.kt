package org.javaopen.edeck

import android.net.Uri

data class RemoteVideo(
    val title: String,
    val uri: Uri,
    val lastModified: Long,
)
