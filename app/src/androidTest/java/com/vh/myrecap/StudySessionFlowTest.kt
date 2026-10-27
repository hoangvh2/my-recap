package com.vh.myrecap

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vh.myrecap.core.Checklist
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.study.StudyEngine
import com.vh.myrecap.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** A checklist with timed lines starts a study session; ending it asks first: "Huỷ" keeps it running, "Kết thúc" stops it. */
@RunWith(AndroidJUnit4::class)
class StudySessionFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private val itemId = "study-flow-test"

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
        app.settings.update { it.copy(homeTab = 0) }
        app.items.upsert(
            Item(
                id = itemId,
                type = ItemType.TASK,
                status = ItemStatus.OPEN,
                title = "Học thử phiên",
                whenAt = System.currentTimeMillis(),
                checklist = Checklist.entriesFrom(listOf("10 phút: Anki ôn thẻ", "5 phút: ghi lỗi")),
            ),
        )
    }

    @After
    fun tearDown() {
        StudyEngine.stop(context)
        app.items.delete(itemId)
    }

    @Test
    fun endingASessionAsksFirst() {
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            assertTrue("row", device.wait(Until.hasObject(By.textStartsWith("Học thử phiên")), 10_000))
            device.findObject(By.descStartsWith("Checklist 0/2")).click()
            assertTrue("start button; on screen: ${onScreen()}", showing(By.textStartsWith("Bắt đầu học")))
            device.findObject(By.textStartsWith("Bắt đầu học")).click()
            assertTrue("running; on screen: ${onScreen()}", showing(By.textStartsWith("Đang học")))
            assertNotNull(StudyEngine.active.value)
            assertEquals(itemId, StudyEngine.active.value?.itemId)

            // "Huỷ": the session keeps running.
            device.findObject(By.text("Kết thúc")).click()
            assertTrue("dialog", device.wait(Until.hasObject(By.text("Kết thúc phiên học?")), 5_000))
            device.findObject(By.text("Huỷ")).click()
            assertTrue(device.wait(Until.gone(By.text("Kết thúc phiên học?")), 5_000))
            assertNotNull(StudyEngine.active.value)

            // "Kết thúc": it stops.
            device.findObject(By.text("Kết thúc")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Kết thúc phiên học?")), 5_000))
            device.findObjects(By.text("Kết thúc")).last().click()
            assertTrue("stopped", waitFor(5_000) { StudyEngine.active.value == null })
            assertTrue("start offered again; on screen: ${onScreen()}", showing(By.textStartsWith("Bắt đầu học")))
            assertNull(StudyEngine.active.value)
        }
    }

    /** The emulator screen is small: look where it is, scrolling the list down a few times if it is not in view yet. */
    private fun showing(selector: androidx.test.uiautomator.BySelector): Boolean {
        if (device.wait(Until.hasObject(selector), 3_000)) return true
        repeat(4) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
            if (device.wait(Until.hasObject(selector), 800)) return true
        }
        return false
    }

    private fun onScreen(): String = device.findObjects(By.pkg(context.packageName))
        .mapNotNull { it.text ?: it.contentDescription }
        .distinct()
        .joinToString(" | ")

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
