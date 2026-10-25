package com.vh.myrecap

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Notes with a kind: the list shows the kind, the filter narrows by kind and by "Chưa xong", the tick marks one read. */
@RunWith(AndroidJUnit4::class)
class NoteKindListTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private val place = "Quán kiểm thử loại A"
    private val read = "Bài kiểm thử loại B"
    private val plain = "Ý kiểm thử loại C"

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
        fun note(id: String, title: String, kind: String?, attrs: Map<String, String> = emptyMap(), url: String? = null, place: String? = null) =
            Item(id = id, type = ItemType.NOTE, status = ItemStatus.OPEN, title = title, createdAt = now, kind = kind, url = url, attrs = attrs, place = place)
        app.items.upsert(note("nkl-place", place, "place", mapOf("dish" to "bún chả"), "https://maps.app.goo.gl/x", "1 Lê Lợi"))
        app.items.upsert(note("nkl-read", read, "read", mapOf("topic" to "mẹo")))
        app.items.upsert(note("nkl-plain", plain, null))
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() = listOf(place, read, plain).forEach { t -> app.items.list().filter { it.title == t }.forEach { app.items.delete(it.id) } }

    private fun openNotesTab() {
        // The type chips scroll sideways; on the small test screen "Ghi chú" may start out of view.
        repeat(4) {
            val chip = device.findObject(By.textStartsWith("Ghi chú").clickable(true))
            if (chip != null) {
                chip.click()
                return
            }
            device.swipe(device.displayWidth - 20, device.displayHeight / 3, 20, device.displayHeight / 3, 10)
        }
        throw AssertionError("notes tab chip not found")
    }

    private fun shows(text: String) = device.wait(Until.hasObject(By.textContains(text)), 5_000)

    @Test
    fun filterNarrowsByKindAndTickMarksARead() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("home", shows("Ghi nhanh"))
            openNotesTab()
            assertTrue("place note", shows(place))
            assertTrue("kind line", device.hasObject(By.textContains("📍 Địa điểm · bún chả")))
            assertTrue(device.hasObject(By.textContains(read)))
            assertTrue(device.hasObject(By.textContains(plain)))

            // Only the reading list.
            device.findObject(By.text("📖 Đọc sau")).click()
            assertTrue("gone", device.wait(Until.gone(By.textContains(place)), 5_000))
            assertTrue(device.hasObject(By.textContains(read)))
            assertFalse(device.hasObject(By.textContains(plain)))

            // The tick marks it read; with "Chưa xong" on, it then leaves the list.
            device.findObject(By.desc("Đánh dấu đã đọc: $read")).click()
            assertTrue("saved", waitFor { app.items.get("nkl-read")?.attrs?.get("read") == "1" })
            assertEquals("1", app.items.get("nkl-read")!!.attrs["read"])
            device.findObject(By.text("Chưa xong")).click()
            assertTrue("filtered out", device.wait(Until.gone(By.textContains(read)), 5_000))
        }
    }

    private fun waitFor(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
