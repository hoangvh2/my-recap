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
            tap { device.findObject(By.descStartsWith("Checklist 0/2")) }
            expectShowing("start button", By.textStartsWith("Bắt đầu học"))
            tap { device.findObject(By.textStartsWith("Bắt đầu học")) }
            expectShowing("running", By.textStartsWith("Đang học"), diagnose = true)
            assertNotNull(StudyEngine.active.value)
            assertEquals(itemId, StudyEngine.active.value?.itemId)

            // "Huỷ": the session keeps running.
            tap { device.findObject(By.text("Kết thúc")) }
            assertTrue("dialog", device.wait(Until.hasObject(By.text("Kết thúc phiên học?")), 5_000))
            tap { device.findObject(By.text("Huỷ")) }
            assertTrue(device.wait(Until.gone(By.text("Kết thúc phiên học?")), 5_000))
            assertNotNull(StudyEngine.active.value)

            // "Kết thúc": it stops.
            tap { device.findObject(By.text("Kết thúc")) }
            assertTrue(device.wait(Until.hasObject(By.text("Kết thúc phiên học?")), 5_000))
            tap { device.findObjects(By.text("Kết thúc")).lastOrNull() }
            assertTrue("stopped", waitFor(5_000) { StudyEngine.active.value == null })
            expectShowing("start offered again", By.textStartsWith("Bắt đầu học"))
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

    /** Finds the node again and taps it; the screen redraws while the session starts and stops, which makes a found node stale. */
    private fun tap(find: () -> androidx.test.uiautomator.UiObject2?) {
        var last: Exception? = null
        repeat(6) {
            try {
                find()?.let { node -> node.click(); return }
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                last = e
            }
            Thread.sleep(300)
        }
        throw AssertionError("could not tap; on screen: ${onScreen()}", last)
    }

    /** Fails with what is on screen (and, for [diagnose], what the engine does) only when [selector] never shows up. */
    private fun expectShowing(label: String, selector: androidx.test.uiautomator.BySelector, diagnose: Boolean = false) {
        if (showing(selector)) return
        throw AssertionError("$label; " + if (diagnose) diagnose() else "on screen: ${onScreen()}")
    }

    /** What the engine does when called directly, to tell "the service did not start" from "the engine did not start". */
    private fun diagnose(): String {
        val before = StudyEngine.active.value
        val services = device.executeShellCommand("dumpsys activity services ${context.packageName}").lines().filter { "StudyService" in it }.take(4).joinToString(" / ")
        val log = device.executeShellCommand("logcat -d -t 400 AndroidRuntime:E ActivityManager:W MyRecap:E *:S").lines().takeLast(14).joinToString(" / ").take(1800)
        val direct = runCatching { StudyEngine.start(context, itemId) }
        return "active before=${before != null}, services=[$services], log=[$log], direct start=${direct.getOrNull()} ${direct.exceptionOrNull()?.stackTraceToString()?.take(600).orEmpty()}, active after=${StudyEngine.active.value != null}; on screen: ${onScreen()}"
    }

    private fun onScreen(): String = device.findObjects(By.pkg(context.packageName))
        .mapNotNull { runCatching { it.text ?: it.contentDescription }.getOrNull() }
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
