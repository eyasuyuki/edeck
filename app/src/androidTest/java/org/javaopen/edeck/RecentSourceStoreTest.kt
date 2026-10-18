package org.javaopen.edeck

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

@RunWith(AndroidJUnit4::class)
class RecentSourceStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun storesAndSwitchesBetweenSmbAndUpnpSources() {
        val prefs = context.getSharedPreferences("recent_source", Context.MODE_PRIVATE)
        withPreferencesRestored(prefs) {
            prefs.edit().clear().commit()
            val smb = RecentSource.Smb("smb://server.local/Movies")
            val upnp = RecentSource.Upnp(
                "Living room", URL("http://192.0.2.10:8200/control"),
                "urn:schemas-upnp-org:service:ContentDirectory:1"
            )

            RecentSourceStore.saveSmb(context, "server.local/Movies")
            RecentSourceStore.saveUpnp(context, UpnpMediaServer(upnp.name, upnp.contentDirectoryUrl, upnp.contentDirectoryUrl, upnp.serviceType))
            assertEquals(listOf(smb, upnp), RecentSourceStore.sources(context))
            assertEquals(upnp, RecentSourceStore.load(context))

            RecentSourceStore.select(context, smb)
            assertEquals(smb, RecentSourceStore.load(context))
            assertEquals(listOf(upnp, smb), RecentSourceStore.sources(context))

            // Updating a source replaces its saved entry instead of creating duplicates.
            RecentSourceStore.saveSmb(context, "smb://server.local/Movies/")
            assertEquals(2, RecentSourceStore.sources(context).size)
            assertEquals(smb, RecentSourceStore.load(context))
        }
    }

    @Test
    fun readsLegacySingleSourcePreferences() {
        val prefs = context.getSharedPreferences("recent_source", Context.MODE_PRIVATE)
        withPreferencesRestored(prefs) {
            prefs.edit().clear()
                .putString("type", "upnp")
                .putString("upnp_name", "Legacy server")
                .putString("upnp_url", "http://192.0.2.11:8200/control")
                .putString("upnp_service", "urn:schemas-upnp-org:service:ContentDirectory:1")
                .commit()

            assertEquals("Legacy server", (RecentSourceStore.load(context) as RecentSource.Upnp).name)
            assertNull(RecentSourceStore.load(context)?.let { it as? RecentSource.Smb })
        }
    }

    private inline fun withPreferencesRestored(prefs: SharedPreferences, block: () -> Unit) {
        val previous = prefs.all
        try {
            block()
        } finally {
            prefs.edit().clear().apply {
                previous.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
        }
    }
}
