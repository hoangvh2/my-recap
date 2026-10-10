package com.vh.myrecap

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.SessionStore
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.recorder.RecordingService
import com.vh.myrecap.ui.MainActivity
import com.vh.myrecap.widget.CaptureWidget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Search, transcript editing, undo, CSV export and the widget / Quick Settings capture entry. */
@RunWith(AndroidJUnit4::class)
class Phase2FlowTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private var folderId: String? = null

    @get:Rule
    val failureShot = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = shot("fail-${description.methodName}")
    }

    @Before
    fun setUp() {
        device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        device.executeShellCommand("wm dismiss-keyguard")
        app.settings.update { it.copy(homeTab = 0) }
        app.items.list().forEach { app.items.delete(it.id) }
        val now = System.currentTimeMillis()
        app.items.upsert(Item("p2-task", ItemType.TASK, ItemStatus.OPEN, "Gửi báo giá cho khách Đông Á", createdAt = now))
        app.items.upsert(
            Item("p2-exp", ItemType.EXPENSE, ItemStatus.OPEN, "Taxi sân bay", whenAt = now, allDay = true, amount = 250_000, category = "Di chuyển", createdAt = now),
        )
        val store = app.store
        val folder = store.create(SessionMode.INTERVIEW, "PV Android – Trần B")
        folderId = folder.id
        store.update(folder.id) {
            it.copy(
                state = RecState.STOPPED,
                durationMs = 90_000,
                segments = listOf(Segment(0, SessionStore.segmentFileName(0), 0, 90_000, TaskStatus.DONE, title = "Kinh nghiệm", recordedAt = now)),
            )
        }
        store.writeTranscript(folder.id, 0, "Người phỏng vấn: Bạn dùng coroutines thế nào?\nỨng viên: Em dùng Kotlin coroutines với Flow.")
        Thread.sleep(500)
    }

    @After
    fun tearDown() {
        app.items.list().forEach { app.items.delete(it.id) }
        folderId?.let { app.store.delete(it) }
    }

    private fun shot(name: String) {
        device.executeShellCommand("mkdir -p $SHOTS")
        device.executeShellCommand("screencap -p $SHOTS/$name.png")
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /** Swipes the list up until [selector] is on screen. */
    private fun scrollTo(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2? {
        // Down first, then back up: after a filter change the list may sit below its target.
        repeat(10) { attempt ->
            device.findObject(selector)?.let { return it }
            val w = device.displayWidth
            val h = device.displayHeight
            if (attempt < 4) device.swipe(w / 2, h * 3 / 4, w / 2, h / 3, 25) else device.swipe(w / 2, h / 3, w / 2, h * 3 / 4, 25)
            Thread.sleep(400)
        }
        return device.findObject(selector)
    }

    private fun launch(intent: Intent = Intent(context, MainActivity::class.java)) =
        ActivityScenario.launch<MainActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @Test
    fun searchFindsItemsAndTranscriptsWithoutAccents() {
        launch().use {
            assertTrue(device.wait(Until.hasObject(By.desc("Tìm kiếm")), 10_000))
            device.findObject(By.desc("Tìm kiếm")).click()
            assertTrue("search field", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))
            device.findObject(By.clazz("android.widget.EditText")).text = "bao gia dong a"
            assertTrue("item found without accents", device.wait(Until.hasObject(By.textContains("Gửi báo giá")), 5_000))
            shot("24-search-items")

            device.findObject(By.clazz("android.widget.EditText")).text = "kotlin flow"
            assertTrue("transcript found", device.wait(Until.hasObject(By.textContains("PV Android")), 5_000))
            shot("25-search-transcript")
            device.findObject(By.textContains("PV Android")).click()
            assertTrue("opens the clip", device.wait(Until.hasObject(By.text("Đoạn 1 / 1")), 5_000))
        }
    }

    @Test
    fun clipTranscriptCanBeEdited() {
        launch(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, folderId)).use {
            assertTrue(device.wait(Until.hasObject(By.text("Kinh nghiệm")), 10_000))
            device.findObject(By.text("Kinh nghiệm")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Đoạn 1 / 1")), 5_000))
            device.findObject(By.desc("Tuỳ chọn")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Sửa transcript")), 5_000))
            device.findObject(By.text("Sửa transcript")).click()
            assertTrue("editor", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))
            device.findObject(By.clazz("android.widget.EditText")).text = "Ứng viên: Em dùng Kotlin coroutines, Flow và Room."
            shot("26-edit-transcript")
            device.findObject(By.text("Lưu")).click()
            assertTrue(
                "transcript saved",
                waitFor(5_000) { app.store.readTranscript(folderId!!, 0)?.contains("Room") == true },
            )
            assertTrue("reader shows the new text", device.wait(Until.hasObject(By.textContains("Flow và Room")), 5_000))
        }
    }

    @Test
    fun deletedItemCanBeRestoredAndExpensesExport() {
        launch().use {
            assertTrue(device.wait(Until.hasObject(By.text("Gửi báo giá cho khách Đông Á")), 10_000))
            device.findObject(By.text("Gửi báo giá cho khách Đông Á")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("Xoá mục")), 5_000))
            device.findObject(By.desc("Xoá mục")).click()
            assertTrue("deleted", waitFor(5_000) { app.items.get("p2-task") == null })
            assertTrue("undo offered", device.wait(Until.hasObject(By.text("Hoàn tác")), 5_000))
            shot("27-undo")
            device.findObject(By.text("Hoàn tác")).click()
            assertTrue("restored", waitFor(5_000) { app.items.get("p2-task") != null })

            // Expenses: the overview card leads to the month view, which exports CSV.
            val card = scrollTo(By.textStartsWith("Chi tiêu tháng"))
            assertNotNull("month spending card", card)
            shot("28a-before-expenses")
            card!!.click()
            Thread.sleep(800)
            shot("28b-expenses-filter")
            val export = scrollTo(By.textContains("CSV"))
            if (export == null) shot("28c-no-export")
            assertNotNull("CSV export button", export)
            shot("28-expenses-export")
            export!!.click()
            assertTrue(
                "share sheet for the CSV",
                device.wait(Until.hasObject(By.pkg(Pattern.compile("com\\.android\\.intentresolver|android"))), 5_000),
            )
            device.pressBack()
        }
    }

    @Test
    fun widgetAndTileIntentStartsACapture() {
        CaptureWidget.refresh(context) // no widget placed on the emulator: must simply do nothing
        launch(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_QUICK_CAPTURE)).use {
            assertTrue("capture started from the shortcut intent", waitFor(10_000) { RecorderState.ui.value.active })
            assertEquals(SessionMode.MEMO, RecorderState.ui.value.mode)
            assertTrue(device.wait(Until.hasObject(By.desc("Xong, phân tích ghi chú")), 5_000))
            val id = RecorderState.ui.value.sessionId
            assertNotNull(id)
            RecordingService.command(context, RecordingService.ACTION_DISCARD)
            assertTrue(waitFor(10_000) { !RecorderState.ui.value.active })
            Thread.sleep(500)
            assertTrue("discarded capture removed", app.store.get(id!!) == null)
        }
    }

    private companion object {
        const val SHOTS = "/data/local/tmp/myrecap-shots"
    }
}
