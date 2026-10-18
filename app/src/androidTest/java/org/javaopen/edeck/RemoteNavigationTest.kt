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
import android.view.KeyEvent

@RunWith(AndroidJUnit4::class)
class RemoteNavigationTest {
    private lateinit var device: UiDevice

    @Before
    fun launchApp() {
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
    fun dpadMovesFocusBetweenSourceActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val refreshLabel = instrumentation.targetContext.getString(R.string.action_refresh)
        val settingsLabel = instrumentation.targetContext.getString(R.string.action_settings)
        val smbLabel = instrumentation.targetContext.getString(R.string.settings_smb)
        val savedSourcesLabel = instrumentation.targetContext.getString(R.string.settings_saved_sources)

        val refreshButton = device.findObject(By.desc(refreshLabel))
        val settingsButton = device.findObject(By.desc(settingsLabel))
        assertTrue("Refresh button should receive initial focus", refreshButton.isFocused)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        device.waitForIdle()
        assertTrue("D-pad down should focus Settings", settingsButton.isFocused)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        device.waitForIdle()
        assertTrue("D-pad up should return focus to Refresh", refreshButton.isFocused)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        device.waitForIdle()
        assertTrue(
            "Enter should open Settings",
            device.wait(Until.hasObject(By.text(savedSourcesLabel)), 3_000)
        )
        assertTrue("SMB source action should be available", device.hasObject(By.text(smbLabel)))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        device.waitForIdle()
        assertTrue(
            "Enter should open the SMB connection form",
            device.wait(Until.hasObject(By.text(instrumentation.targetContext.getString(R.string.title_connect_smb))), 3_000)
        )
    }
}
