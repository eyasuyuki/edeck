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
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertTrue("Main screen did not appear", device.wait(Until.hasObject(By.textContains("DLNAを検索")), 5_000))
    }

    @Test
    fun dpadMovesFocusBetweenSourceActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val upnpButton = device.findObject(By.textContains("DLNAを検索"))
        val smbButton = device.findObject(By.text("SMB共有を開く"))

        assertTrue("UPnP button should receive initial focus", upnpButton.isFocused)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        device.waitForIdle()
        assertTrue("D-pad right should focus SMB", smbButton.isFocused)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        device.waitForIdle()
        assertTrue("D-pad left should return focus to UPnP", upnpButton.isFocused)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        device.waitForIdle()
        assertTrue(
            "Enter should open the SMB connection form",
            device.wait(Until.hasObject(By.text("SMB共有に接続")), 3_000)
        )
    }
}
