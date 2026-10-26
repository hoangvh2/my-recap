package com.vh.myrecap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vh.myrecap.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** With text on the clipboard, a "Dán" button over the keyboard pastes it into the focused field. */
@RunWith(AndroidJUnit4::class)
class PasteButtonTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val pasted = "Nội dung dán thử 4821"

    @get:Rule
    val failureShot = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            device.executeShellCommand("mkdir -p /data/local/tmp/myrecap-shots")
            device.executeShellCommand("screencap -p /data/local/tmp/myrecap-shots/fail-${description.methodName}.png")
        }
    }

    @Before
    fun setUp() {
        device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        device.executeShellCommand("wm dismiss-keyguard")
    }

    private fun setClipboard(text: String?) = instrumentation.runOnMainSync {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (text == null) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("test", text))
    }

    @Test
    fun pasteButtonPutsTheClipboardInTheFocusedField() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("home", device.wait(Until.hasObject(By.desc("Tìm kiếm")), 10_000))
            device.findObject(By.desc("Tìm kiếm")).click()
            assertTrue("search field", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))

            // No text on the clipboard: no button.
            setClipboard(null)
            device.findObject(By.clazz("android.widget.EditText")).click()
            Thread.sleep(1_500)
            assertTrue("no button without clipboard text", !device.hasObject(By.text("Dán")))

            setClipboard(pasted)
            assertTrue("paste button", device.wait(Until.hasObject(By.text("Dán")), 10_000))
            device.findObject(By.text("Dán")).click()
            assertTrue("text pasted", device.wait(Until.hasObject(By.textContains(pasted)), 5_000))
        }
        setClipboard(null)
    }
}
