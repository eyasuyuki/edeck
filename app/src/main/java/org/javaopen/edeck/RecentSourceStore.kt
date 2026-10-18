package org.javaopen.edeck

import android.content.Context
import java.net.URL

sealed class RecentSource {
    abstract val cacheKey: String

    data class Smb(val address: String) : RecentSource() {
        override val cacheKey: String get() = "smb:${address.trimEnd('/')}"
    }

    data class Upnp(val name: String, val contentDirectoryUrl: URL, val serviceType: String) : RecentSource() {
        override val cacheKey: String get() = "upnp:${contentDirectoryUrl.toExternalForm()}"
    }
}

object RecentSourceStore {
    private const val PREFS = "recent_source"
    private const val TYPE = "type"
    private const val SMB_ADDRESS = "smb_address"
    private const val UPNP_NAME = "upnp_name"
    private const val UPNP_URL = "upnp_url"
    private const val UPNP_SERVICE = "upnp_service"

    fun saveSmb(context: Context, address: String) {
        preferences(context).edit()
            .putString(TYPE, "smb")
            .putString(SMB_ADDRESS, address.trim().let { if (it.startsWith("smb://", true)) it else "smb://$it" })
            .remove(UPNP_NAME)
            .remove(UPNP_URL)
            .remove(UPNP_SERVICE)
            .apply()
    }

    fun saveUpnp(context: Context, server: UpnpMediaServer) {
        preferences(context).edit()
            .putString(TYPE, "upnp")
            .putString(UPNP_NAME, server.name)
            .putString(UPNP_URL, server.contentDirectoryUrl.toExternalForm())
            .putString(UPNP_SERVICE, server.serviceType)
            .remove(SMB_ADDRESS)
            .apply()
    }

    fun load(context: Context): RecentSource? = runCatching {
        val prefs = preferences(context)
        when (prefs.getString(TYPE, null)) {
            "smb" -> prefs.getString(SMB_ADDRESS, null)?.let(RecentSource::Smb)
            "upnp" -> {
                val name = prefs.getString(UPNP_NAME, null) ?: return null
                val url = prefs.getString(UPNP_URL, null)?.let(::URL) ?: return null
                val service = prefs.getString(UPNP_SERVICE, null) ?: return null
                RecentSource.Upnp(name, url, service)
            }
            else -> null
        }
    }.getOrNull()

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
