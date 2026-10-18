package org.javaopen.edeck

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmbCredentialsStoreTest {
    @Test
    fun playbackCanResolveShareCredentialsFromVideoFileUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.currentTimeMillis()
        val shareUri = "smb://credentials-test-$suffix.local/Media"
        val fileUri = "$shareUri/Movies/sample.mp4"
        val unrelatedFileUri = "smb://credentials-test-$suffix.local/Other/movie.mp4"
        val expected = SmbCredentials("WORKGROUP", "player", "secret-$suffix")
        val preferences = context.getSharedPreferences("smb_connections", Context.MODE_PRIVATE)
        val previousLastSource = preferences.getString("last_source", null)

        try {
            SmbCredentialsStore.remember(context, shareUri, expected)

            assertEquals(expected, SmbCredentialsStore.credentialsForResource(context, fileUri))
            assertNotNull(SmbRepository.contextForPlayback(context, fileUri))
            assertNull(SmbCredentialsStore.credentialsForResource(context, unrelatedFileUri))
        } finally {
            preferences.edit().apply {
                if (previousLastSource == null) remove("last_source")
                else putString("last_source", previousLastSource)
            }.commit()
        }
    }

    @Test
    fun credentialsForMultipleSavedSharesAreResolvedByMostSpecificRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.currentTimeMillis()
        val host = "multi-$suffix.local"
        val broadShare = "smb://$host/Media"
        val nestedShare = "$broadShare/Private"
        val broadCredentials = SmbCredentials("WORKGROUP", "general", "general-$suffix")
        val nestedCredentials = SmbCredentials("WORKGROUP", "private", "private-$suffix")
        val smbPreferences = context.getSharedPreferences("smb_connections", Context.MODE_PRIVATE)
        val sourcePreferences = context.getSharedPreferences("recent_source", Context.MODE_PRIVATE)
        val oldSmb = smbPreferences.all
        val oldSources = sourcePreferences.all

        try {
            SmbCredentialsStore.remember(context, broadShare, broadCredentials)
            SmbCredentialsStore.remember(context, nestedShare, nestedCredentials)
            RecentSourceStore.saveSmb(context, broadShare)
            RecentSourceStore.saveSmb(context, nestedShare)

            assertEquals(broadCredentials, SmbCredentialsStore.credentialsForResource(context, "$broadShare/Movies/a.mp4"))
            assertEquals(nestedCredentials, SmbCredentialsStore.credentialsForResource(context, "$nestedShare/a.mp4"))
            assertNull(SmbCredentialsStore.credentialsForResource(context, "smb://$host/Media2/a.mp4"))
        } finally {
            restorePreferences(smbPreferences, oldSmb)
            restorePreferences(sourcePreferences, oldSources)
        }
    }

    private fun restorePreferences(preferences: android.content.SharedPreferences, values: Map<String, *>) {
        preferences.edit().clear().apply {
            values.forEach { (key, value) ->
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
