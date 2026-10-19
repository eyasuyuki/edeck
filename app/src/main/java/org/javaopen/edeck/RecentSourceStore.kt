package org.javaopen.edeck

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

sealed class RecentSource {
    // The key identifies cache entries and the current selection across launches, so it
    // must depend on stable connection details rather than a display name alone.
    abstract val cacheKey: String
    abstract val label: String

    data class Smb(val address: String) : RecentSource() {
        override val cacheKey: String get() = "smb:${address.trimEnd('/')}"
        override val label: String get() = address
    }

    data class Upnp(val name: String, val contentDirectoryUrl: URL, val serviceType: String) : RecentSource() {
        override val cacheKey: String get() = "upnp:${contentDirectoryUrl.toExternalForm()}"
        override val label: String get() = name
    }
}

object RecentSourceStore {
    private const val PREFS = "recent_source"
    private const val SOURCES = "sources"
    private const val CURRENT_ID = "current_id"
    private const val TYPE = "type"
    private const val SMB_ADDRESS = "smb_address"
    private const val UPNP_NAME = "upnp_name"
    private const val UPNP_URL = "upnp_url"
    private const val UPNP_SERVICE = "upnp_service"

    // Preferences hold both the source collection and current ID; synchronize compound
    // reads/writes so two callbacks cannot each overwrite the other's source update.
    @Synchronized
    fun sources(context: Context): List<RecentSource> {
        val prefs = preferences(context)
        val stored = prefs.getString(SOURCES, null)
        // Keep reading the former single-source format so users upgrading from older
        // builds do not lose the connection they had already configured.
        if (stored != null) return parseSources(stored)
        return legacySource(prefs)?.let(::listOf).orEmpty()
    }

    @Synchronized
    fun load(context: Context): RecentSource? {
        val prefs = preferences(context)
        val allSources = sources(context)
        val currentId = prefs.getString(CURRENT_ID, null)
        // CURRENT_ID preserves the user's last choice; the first saved source is a
        // migration/fallback path for preferences created before multiple sources existed.
        return allSources.firstOrNull { it.cacheKey == currentId } ?: allSources.firstOrNull()
    }

    @Synchronized
    fun select(context: Context, source: RecentSource) {
        val selected = when (source) {
            is RecentSource.Smb -> source.copy(address = normalizeSmbAddress(source.address))
            is RecentSource.Upnp -> source
        }
        val prefs = preferences(context)
        // Replace by cache key so reconnecting to the same share refreshes its record
        // instead of creating duplicate entries in the source picker.
        val allSources = (sources(context).filterNot { it.cacheKey == selected.cacheKey } + selected)
        prefs.edit()
            .putString(SOURCES, serializeSources(allSources))
            .putString(CURRENT_ID, selected.cacheKey)
            .apply()
    }

    @Synchronized
    fun saveSmb(context: Context, address: String) = select(
        context,
        RecentSource.Smb(normalizeSmbAddress(address))
    )

    @Synchronized
    fun saveUpnp(context: Context, server: UpnpMediaServer) = select(
        context,
        RecentSource.Upnp(server.name, server.contentDirectoryUrl, server.serviceType)
    )

    private fun serializeSources(sources: List<RecentSource>): String = JSONArray().apply {
        sources.forEach { source ->
            put(JSONObject().apply {
                when (source) {
                    is RecentSource.Smb -> {
                        put("type", "smb")
                        put("address", source.address)
                    }
                    is RecentSource.Upnp -> {
                        put("type", "upnp")
                        put("name", source.name)
                        put("url", source.contentDirectoryUrl.toExternalForm())
                        put("service", source.serviceType)
                    }
                }
            })
        }
    }.toString()

    private fun parseSources(value: String): List<RecentSource> = runCatching {
        val json = JSONArray(value)
        buildList {
            for (index in 0 until json.length()) {
                val entry = json.getJSONObject(index)
                when (entry.optString("type")) {
                    "smb" -> entry.optString("address").takeIf(String::isNotBlank)?.let {
                        add(RecentSource.Smb(it))
                    }
                    "upnp" -> {
                        val name = entry.optString("name")
                        val url = entry.optString("url")
                        val service = entry.optString("service")
                        if (name.isNotBlank() && url.isNotBlank() && service.isNotBlank()) {
                            add(RecentSource.Upnp(name, URL(url), service))
                        }
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun legacySource(prefs: android.content.SharedPreferences): RecentSource? = runCatching {
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

    private fun normalizeSmbAddress(address: String): String {
        // A trailing slash is not a different SMB share; normalize it so selection,
        // credential lookup, and cached lists use one identity for that connection.
        val withScheme = address.trim().let { if (it.startsWith("smb://", true)) it else "smb://$it" }
        return withScheme.trimEnd('/')
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
