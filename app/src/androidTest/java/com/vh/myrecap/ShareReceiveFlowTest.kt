package com.vh.myrecap

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vh.myrecap.core.ItemType
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

/** Share sheet → a note opens in the editor already filled in; leaving it unsaved asks first. */
@RunWith(AndroidJUnit4::class)
class ShareReceiveFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private val title = "Quán phở Lệ test chia sẻ"

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
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() = app.items.list().filter { it.title == title }.forEach { app.items.delete(it.id) }

    // The address is in the text, so nothing here depends on the network: the page lookup just finds nothing.
    private fun share() = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, "Ngon\n📍 5 Lê Lợi, Q.1\nhttps://example.invalid/post/1")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test
    fun sharedTextOpensFilledNoteAndLeavingAsksFirst() {
        ActivityScenario.launch<MainActivity>(share()).use {
            assertTrue("editor", device.wait(Until.hasObject(By.text(title)), 15_000))
            assertTrue("address", device.hasObject(By.text("5 Lê Lợi, Q.1")))

            // "Không": the note stays on screen and nothing is saved.
            device.pressBack()
            assertTrue("dialog", device.wait(Until.hasObject(By.text("Bỏ ghi chú này?")), 5_000))
            device.findObject(By.text("Huỷ")).click()
            assertTrue(device.wait(Until.gone(By.text("Bỏ ghi chú này?")), 5_000))
            assertTrue(device.hasObject(By.text(title)))
            assertTrue(app.items.list().none { it.title == title })

            // "Bỏ": it goes, still nothing saved.
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Bỏ ghi chú này?")), 5_000))
            device.findObject(By.text("Bỏ")).click()
            assertTrue("closed", device.wait(Until.gone(By.text("Bỏ ghi chú này?")), 5_000))
            assertTrue(app.items.list().none { it.title == title })
        }
    }

    @Test
    fun savingKeepsTheLinkAndAddressInTheNote() {
        ActivityScenario.launch<MainActivity>(share()).use {
            assertTrue("editor", device.wait(Until.hasObject(By.text(title)), 15_000))
            device.findObject(By.text("Lưu")).click()
            assertTrue("saved", waitFor(10_000) { app.items.list().any { it.title == title } })
            val note = app.items.list().first { it.title == title }
            assertEquals(ItemType.NOTE, note.type)
            assertEquals("5 Lê Lợi, Q.1", note.place)
            assertTrue(note.details.contains("https://example.invalid/post/1"))
            // The address makes it a place note, and the link is kept in its own field too.
            assertEquals("place", note.kind)
            assertEquals("https://example.invalid/post/1", note.url)
        }
    }

    @Test
    fun aSharedArticleIsReadLaterAndTheKindCanBeChangedBeforeSaving() {
        val tipTitle = "Mẹo pin test chia sẻ"
        try {
            val tip = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, tipTitle)
                .putExtra(Intent.EXTRA_TEXT, "Tắt 5G khi không cần\nhttps://example.invalid/tip/1")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(tip).use {
                assertTrue("editor", device.wait(Until.hasObject(By.text(tipTitle)), 15_000))
                assertTrue("kind chips", device.hasObject(By.text("📖 Đọc sau")))
                // Saved as shared: read later, with the source.
                device.findObject(By.text("Lưu")).click()
                assertTrue("saved", waitFor(10_000) { app.items.list().any { it.title == tipTitle } })
                val note = app.items.list().first { it.title == tipTitle }
                assertEquals("read", note.kind)
                assertEquals("https://example.invalid/tip/1", note.url)
                assertEquals("example.invalid", note.attrs["source"])
            }
            app.items.list().filter { it.title == tipTitle }.forEach { app.items.delete(it.id) }
            ActivityScenario.launch<MainActivity>(tip).use {
                assertTrue("editor", device.wait(Until.hasObject(By.text(tipTitle)), 15_000))
                device.findObject(By.text("▶ Video")).click()
                device.findObject(By.text("Lưu")).click()
                assertTrue("saved", waitFor(10_000) { app.items.list().any { it.title == tipTitle } })
                val note = app.items.list().first { it.title == tipTitle }
                assertEquals("video", note.kind)
                assertTrue("a kind's old values are dropped", note.attrs.isEmpty())
            }
        } finally {
            app.items.list().filter { it.title == tipTitle }.forEach { app.items.delete(it.id) }
        }
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
