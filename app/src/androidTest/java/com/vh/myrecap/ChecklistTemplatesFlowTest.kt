package com.vh.myrecap

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vh.myrecap.core.ChecklistTemplate
import com.vh.myrecap.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Settings → Mẫu checklist: a delete asks first, "Không" keeps the template, "Xoá" removes it and Undo brings it back. */
@RunWith(AndroidJUnit4::class)
class ChecklistTemplatesFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private var saved: List<ChecklistTemplate> = emptyList()

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
        saved = app.settings.current.checklistTemplates
        app.settings.update { it.copy(homeTab = 0, checklistTemplates = listOf(ChecklistTemplate("t1", "Ra cửa hàng", listOf("Chìa khoá", "Tiền lẻ")))) }
    }

    @After
    fun tearDown() {
        app.settings.update { it.copy(checklistTemplates = saved) }
    }

    @Test
    fun deleteAsksFirstThenUndo() {
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            assertTrue("agenda", device.wait(Until.hasObject(By.desc("Cài đặt")), 10_000))
            device.findObject(By.desc("Cài đặt")).click()
            assertTrue("settings", device.wait(Until.hasObject(By.textStartsWith("Quản lý mẫu checklist")), 10_000) || scrollTo("Quản lý mẫu checklist"))
            Thread.sleep(700) // let the scroll settle before tapping
            device.findObject(By.textStartsWith("Quản lý mẫu checklist")).click()
            assertTrue("screen", device.wait(Until.hasObject(By.text("Ra cửa hàng")), 5_000))

            // "Không": the template stays.
            device.findObject(By.desc("Xoá mẫu Ra cửa hàng")).click()
            assertTrue("dialog", device.wait(Until.hasObject(By.text("Xoá mẫu này?")), 5_000))
            device.findObject(By.text("Huỷ")).click()
            assertTrue(device.wait(Until.gone(By.text("Xoá mẫu này?")), 5_000))
            assertEquals(1, app.settings.current.checklistTemplates.size)

            // "Xoá": it goes, Undo restores it.
            device.findObject(By.desc("Xoá mẫu Ra cửa hàng")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Xoá mẫu này?")), 5_000))
            device.findObject(By.text("Xoá")).click()
            assertTrue("deleted", waitFor(5_000) { app.settings.current.checklistTemplates.isEmpty() })
            assertTrue("undo offered", device.wait(Until.hasObject(By.text("Hoàn tác")), 5_000))
            device.findObject(By.text("Hoàn tác")).click()
            assertTrue("restored", waitFor(5_000) { app.settings.current.checklistTemplates.map { it.id } == listOf("t1") })
        }
    }

    private fun scrollTo(text: String): Boolean {
        repeat(6) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            if (device.wait(Until.hasObject(By.textStartsWith(text)), 800)) return true
        }
        return false
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
