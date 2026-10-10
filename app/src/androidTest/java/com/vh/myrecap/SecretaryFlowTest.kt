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
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.ProviderKind
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.SessionStore
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.reminder.Reminders
import com.vh.myrecap.ui.MainActivity
import com.vh.myrecap.work.ProcessWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Quick capture → AI proposals → 1-tap confirmation → items in the secretary lists, with the real
 * worker against a fake OpenAI-compatible server. Also checks the capture audio is deleted once it
 * has been analysed, and that a cancelled capture leaves nothing behind.
 */
@RunWith(AndroidJUnit4::class)
class SecretaryFlowTest {
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
    private val tomorrow = LocalDate.now().plusDays(1)
    private lateinit var server: FakeOpenAiServer

    @get:Rule
    val failureShot = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = shot("fail-${description.methodName}")
    }

    @Before
    fun setUp() {
        device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        device.executeShellCommand("wm dismiss-keyguard")
        server = FakeOpenAiServer { user ->
            if ("Ghi chú:" in user) {
                """
                {"items":[
                  {"type":"event","title":"Họp với anh Nam","details":"","date":"$tomorrow","time":"15:00","place":"Văn phòng","person":"anh Nam","quote":"mai 3 giờ chiều họp anh Nam ở văn phòng"},
                  {"type":"expense","title":"Ăn trưa phở","date":null,"time":null,"amount":"65k","category":"Ăn uống","quote":"trưa nay ăn phở 65 nghìn"},
                  {"type":"task","title":"Gửi báo giá cho khách","details":"","date":"$tomorrow","time":null,"quote":"nhớ gửi báo giá cho khách"},
                  {"type":"note","title":"Ý tưởng widget ghi nhanh","details":"Widget ngoài màn hình chính","date":null,"time":null,"quote":"ý tưởng làm widget ghi nhanh"}
                ]}
                """.trimIndent()
            } else {
                "{}"
            }
        }
        app.settings.update {
            it.copy(
                sttProvider = ProviderKind.OPENAI_COMPATIBLE,
                summaryProvider = ProviderKind.OPENAI_COMPATIBLE,
                openAiBaseUrl = server.baseUrl,
                openAiKey = "test-key",
                openAiChatModel = "fake",
                keepMemoAudio = false,
                homeTab = 0,
            )
        }
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        server.close()
        app.items.list().forEach { app.items.delete(it.id) }
        app.store.list().filter { it.isMemo }.forEach { app.store.delete(it.id) }
    }

    private fun shot(name: String) {
        device.executeShellCommand("mkdir -p $SHOTS")
        device.executeShellCommand("screencap -p $SHOTS/$name.png")
    }

    /** Taps a filter chip, swiping the chip row sideways until it is on screen. */
    private fun clickChip(label: String) {
        val row = device.findObject(By.textStartsWith("Tổng quan")) ?: device.findObject(By.textStartsWith("Chi tiêu"))
            ?: device.findObject(By.textStartsWith("Việc"))
        assertNotNull("filter chips not on screen", row)
        val y = row!!.visibleBounds.centerY()
        val w = device.displayWidth
        repeat(4) { attempt ->
            device.findObject(By.textStartsWith(label))?.let {
                it.click()
                return
            }
            // First look to the right, then back to the left.
            if (attempt < 2) device.swipe(w * 3 / 4, y, w / 4, y, 20) else device.swipe(w / 4, y, w * 3 / 4, y, 20)
            Thread.sleep(400)
        }
        throw AssertionError("chip $label not found")
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun captureIsAnalysedConfirmedAndListed() = runBlocking {
        val store = app.store
        val memo = store.create(SessionMode.MEMO, "Ghi nhanh test")
        assertEquals(TaskStatus.PENDING, memo.extract)
        val seg = Segment(0, SessionStore.segmentFileName(0), 0, 9_000, TaskStatus.DONE, recordedAt = System.currentTimeMillis())
        store.update(memo.id) { it.copy(state = RecState.STOPPED, durationMs = 9_000, segments = listOf(seg)) }
        store.audioFile(memo.id, seg).writeBytes(ByteArray(64_000))
        store.writeTranscript(
            memo.id, 0,
            "Mai 3 giờ chiều họp anh Nam ở văn phòng. Trưa nay ăn phở 65 nghìn. Nhớ gửi báo giá cho khách. " +
                "Ý tưởng: làm widget ghi nhanh.",
        )

        val result = TestListenableWorkerBuilder<ProcessWorker>(context)
            .setInputData(workDataOf(ProcessWorker.KEY_ID to memo.id))
            .build()
            .doWork()
        val analysed = store.get(memo.id)!!
        assertEquals("error=${analysed.error} server=${server.errors}", ListenableWorker.Result.success(), result)
        assertEquals(TaskStatus.DONE, analysed.extract)
        assertTrue("prompt carries the capture", server.requests.single().contains("Nhớ gửi báo giá"))

        val drafts = app.items.bySource(memo.id)
        assertEquals(4, drafts.size)
        assertTrue(drafts.all { it.status == ItemStatus.DRAFT })
        assertEquals(65_000L, drafts.single { it.type == ItemType.EXPENSE }.amount)
        // Storage policy: capture audio goes as soon as its words are saved.
        assertFalse("audio flag", analysed.segments.single().hasAudio)
        assertFalse("audio file deleted", store.audioFile(memo.id, seg).exists())

        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_SESSION_ID, memo.id)
        ActivityScenario.launch<MainActivity>(launch).use {
            assertTrue("review screen not shown", device.wait(Until.hasObject(By.text("Bạn đã nói")), 10_000))
            assertTrue(device.wait(Until.hasObject(By.text("Họp với anh Nam")), 5_000))
            Thread.sleep(500)
            shot("16-capture-review")

            assertTrue(device.wait(Until.hasObject(By.text("Lưu tất cả 4 mục")), 5_000))
            device.findObject(By.text("Lưu tất cả 4 mục")).click()
            assertTrue("items confirmed", waitFor(5_000) { app.items.bySource(memo.id).all { it.status == ItemStatus.OPEN } })
            assertTrue(device.wait(Until.hasObject(By.text("Đã lưu từ ghi chú này")), 5_000))
            shot("17-capture-saved")
            val event = app.items.bySource(memo.id).single { it.type == ItemType.EVENT }
            assertNotNull("appointment gets a reminder", Reminders.triggerAt(event))

            device.pressBack()
            assertTrue("secretary tab not shown", device.wait(Until.hasObject(By.text("Gửi báo giá cho khách")), 5_000))
            Thread.sleep(500)
            shot("18-agenda")

            // The filter chips scroll sideways on a narrow screen.
            clickChip("Chi tiêu")
            assertTrue("expense total", device.wait(Until.hasObject(By.text("65.000 đ")), 5_000))
            shot("19-expenses")

            clickChip("Việc")
            assertTrue(device.wait(Until.hasObject(By.text("Gửi báo giá cho khách")), 5_000))
            device.findObject(By.text("Gửi báo giá cho khách")).click()
            assertTrue("item editor not shown", device.wait(Until.hasObject(By.text("Lưu thay đổi")), 5_000))
            assertTrue("source link", device.wait(Until.hasObject(By.textStartsWith("Từ ghi nhanh")), 5_000))
            shot("20-item-editor")
            device.findObject(By.text("Xong")).click()
            val task = app.items.bySource(memo.id).single { it.type == ItemType.TASK }
            assertTrue("task completed", waitFor(5_000) { app.items.get(task.id)?.status == ItemStatus.DONE })

            device.wait(Until.hasObject(By.textStartsWith("Đã xong")), 5_000)
            device.executeShellCommand("cmd uimode night yes")
            Thread.sleep(2_500)
            clickChip("Tổng quan")
            Thread.sleep(800)
            shot("21-dark-agenda")
            device.executeShellCommand("cmd uimode night no")
            Thread.sleep(1_500)
        }
    }

    @Test
    fun cancelledCaptureLeavesNothing() {
        val before = app.store.list().count { it.isMemo }
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            assertTrue("capture button", device.wait(Until.hasObject(By.desc("Ghi nhanh")), 10_000))
            shot("22-agenda-empty")
            device.findObject(By.desc("Ghi nhanh")).click()
            assertTrue("capture did not start", waitFor(10_000) { RecorderState.ui.value.active })
            assertTrue(device.wait(Until.hasObject(By.desc("Xong, phân tích ghi chú")), 5_000))
            Thread.sleep(1_500)
            shot("23-capture-recording")
            device.findObject(By.desc("Huỷ ghi nhanh")).click()
            assertTrue("capture did not stop", waitFor(10_000) { !RecorderState.ui.value.active })
            Thread.sleep(1_000)
            assertEquals("cancelled capture removed", before, app.store.list().count { it.isMemo })
            assertNull(RecorderState.ui.value.finishedSessionId)
        }
    }

    private companion object {
        const val SHOTS = "/data/local/tmp/myrecap-shots"
    }
}
