package com.vh.myrecap

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Editing an item must bring the person back to the list they came from: after saving an expense, the
 * next expense is one tap away (no hop back to "Tổng quan"). The same for tasks and appointments.
 */
@RunWith(AndroidJUnit4::class)
class KeepTabAfterEditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private val ids = listOf("kt-task-a", "kt-task-b", "kt-event-a", "kt-event-b", "kt-exp-a", "kt-exp-b")

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
        cleanUp()
        val now = System.currentTimeMillis()
        val soon = now + 3_600_000
        fun item(id: String, type: ItemType, title: String, amount: Long? = null) =
            Item(id = id, type = type, status = ItemStatus.OPEN, title = title, createdAt = now, whenAt = soon, amount = amount)
        app.items.upsert(item("kt-task-a", ItemType.TASK, "Dọn giữ tab A"))
        app.items.upsert(item("kt-task-b", ItemType.TASK, "Dọn giữ tab B"))
        app.items.upsert(item("kt-event-a", ItemType.EVENT, "Hẹn giữ tab A"))
        app.items.upsert(item("kt-event-b", ItemType.EVENT, "Hẹn giữ tab B"))
        app.items.upsert(item("kt-exp-a", ItemType.EXPENSE, "Chi giữ tab A", 50_000))
        app.items.upsert(item("kt-exp-b", ItemType.EXPENSE, "Chi giữ tab B", 70_000))
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() = ids.forEach { app.items.delete(it) }

    private fun find(selector: BySelector): UiObject2? {
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(9) { attempt ->
            device.wait(Until.hasObject(selector), 1_500)
            device.findObject(selector)?.let { return it }
            if (attempt < 4) device.swipe(w / 2, h * 3 / 4, w / 2, h / 3, 25) else device.swipe(w / 2, h / 3, w / 2, h * 3 / 4, 25)
            Thread.sleep(300)
        }
        return device.findObject(selector)
    }

    /** The filter chip [label]: other texts can start the same ("Chi tiêu tháng 11" in the overview), so take the one on the chip row. */
    private fun chip(label: String): UiObject2 {
        val row = checkNotNull(find(By.textStartsWith("Tổng quan"))) { "chip row" }
        val y = row.visibleBounds.centerY()
        val w = device.displayWidth
        repeat(6) { attempt ->
            device.findObjects(By.textStartsWith(label)).firstOrNull { kotlin.math.abs(it.visibleBounds.centerY() - y) < 40 }?.let { return it }
            if (attempt < 3) device.swipe(w * 3 / 4, y, w / 4, y, 20) else device.swipe(w / 4, y, w * 3 / 4, y, 20)
            // The row keeps gliding after the swipe; a tap on the spot where the chip was a moment ago misses it.
            device.waitForIdle()
            Thread.sleep(600)
        }
        // Say what the screen shows, so a failure in CI can be read from the log.
        val seen = device.findObjects(By.text(java.util.regex.Pattern.compile(".+"))).joinToString(" | ") { "${it.text}@${it.visibleBounds.centerX()},${it.visibleBounds.centerY()}" }
        throw AssertionError("chip $label not found; row y=$y; on screen: ${seen.take(1500)}")
    }

    /** Taps the chip until [gone] has left the screen: a tap that lands while the row still glides selects nothing, so it is repeated (the check itself is as strict as before). */
    private fun selectChip(label: String, gone: String) {
        repeat(3) {
            chip(label).click()
            if (device.wait(Until.gone(By.textContains(gone)), 3_000)) return
        }
    }

    private fun shows(text: String) = find(By.textContains(text)) != null

    /** Opens [first] from the [chipLabel] list, renames it and saves; then [second] must be on screen, [other] must not. */
    private fun editKeepsList(chipLabel: String, first: String, second: String, other: String) {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("home", device.wait(Until.hasObject(By.textStartsWith("Tổng quan")), 8_000))
            selectChip(chipLabel, gone = other)
            assertFalse("$other hidden under $chipLabel", device.hasObject(By.textContains(other)))
            checkNotNull(find(By.textContains(first))) { "first row" }.click()
            assertTrue("editor", device.wait(Until.hasObject(By.text("Lưu thay đổi")), 5_000) || find(By.text("Lưu thay đổi")) != null)
            val field = checkNotNull(find(By.text(first))) { "title field" }
            field.text = "$first sửa"
            checkNotNull(find(By.text("Lưu thay đổi"))) { "save" }.click()
            assertTrue("back on list", device.wait(Until.hasObject(By.textContains("$first sửa")), 8_000))
            // Still the same list: the second item is there, and the other type is not (the overview would show it).
            assertTrue("second row", shows(second))
            assertFalse("$other still hidden after save", device.hasObject(By.textContains(other)))
        }
    }

    @Test
    fun expenseEditStaysOnExpenses() = editKeepsList("Chi tiêu", "Chi giữ tab A", "Chi giữ tab B", "Dọn giữ tab")

    @Test
    fun taskEditStaysOnTasks() = editKeepsList("Việc", "Dọn giữ tab A", "Dọn giữ tab B", "Hẹn giữ tab")

    @Test
    fun eventEditStaysOnAppointments() = editKeepsList("Lịch hẹn", "Hẹn giữ tab A", "Hẹn giữ tab B", "Dọn giữ tab")
}
