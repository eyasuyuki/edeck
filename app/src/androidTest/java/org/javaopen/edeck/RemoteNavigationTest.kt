package org.javaopen.edeck

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteNavigationTest {
    private lateinit var device: UiDevice

    @Before
    fun launchApp() {
        // Start from a predictable launcher state because Android TV may retain the last
        // focused widget across Activity launches, hiding focus-navigation regressions.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.pressHome()
        device.waitForIdle()
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        val refreshLabel = instrumentation.targetContext.getString(R.string.action_refresh)
        device.waitForIdle()
        assertTrue("Main screen did not appear", device.wait(Until.hasObject(By.desc(refreshLabel)), 15_000))
    }

    @Test
    fun settingsDialogOpensSmbConnectionForm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val refreshLabel = instrumentation.targetContext.getString(R.string.action_refresh)
        val settingsLabel = instrumentation.targetContext.getString(R.string.action_settings)
        val smbLabel = instrumentation.targetContext.getString(R.string.settings_smb)
        val savedSourcesLabel = instrumentation.targetContext.getString(R.string.settings_saved_sources)

        val refreshButton = device.findObject(By.desc(refreshLabel))
        assertTrue("Refresh button should receive initial focus", refreshButton.isFocused)

        // Refresh is in the content pane and Settings is in the side rail, so navigation
        // between them depends on spatial focus and should not be asserted as a vertical step.
        device.findObject(By.desc(settingsLabel)).click()
        device.waitForIdle()
        assertTrue(
            "Settings action should open its options",
            device.wait(Until.hasObject(By.text(savedSourcesLabel)), 3_000)
        )
        assertTrue("SMB source action should be available", device.hasObject(By.text(smbLabel)))
        device.findObject(By.text(smbLabel)).click()
        device.waitForIdle()
        assertTrue(
            "SMB option should open the connection form",
            device.wait(Until.hasObject(By.text(instrumentation.targetContext.getString(R.string.title_connect_smb))), 3_000)
        )
    }
}
