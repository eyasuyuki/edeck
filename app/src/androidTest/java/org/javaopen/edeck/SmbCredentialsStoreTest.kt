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
}
