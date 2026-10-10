package com.vh.myrecap

import android.Manifest
import android.app.KeyguardManager
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
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.SessionStore
import com.vh.myrecap.data.SummaryJob
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.recorder.RecordingService
import com.vh.myrecap.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * End-to-end on a device/emulator: start from the UI, record with the real microphone, bookmark
 * from the notification path, lock/unlock the screen, stop, and check what was saved. Saves
 * screenshots to /data/local/tmp/myrecap-shots for visual review (CI pulls them).
 */
@RunWith(AndroidJUnit4::class)
class RecordingFlowTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    /** Screenshot of whatever is on screen when a test fails, for diagnosis from CI artifacts. */
    @get:Rule
    val failureShot = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = shot("fail-${description.methodName}")
    }

    /** Earlier tests run without UI; the emulator screen may have timed out and locked meanwhile. */
    @Before
    fun screenOnAndUnlocked() {
        device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        device.executeShellCommand("wm dismiss-keyguard")
        Thread.sleep(500)
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

    @Test
    fun recordBookmarkLockAndStop() {
        // The emulator microphone delivers silence, which voice detection (tested in ClipRecorderTest)
        // would rightly drop. Record everything here to exercise the service, screens and storage.
        // Start on the recordings tab (the app opens on the secretary tab by default).
        MyRecapApp.from(context).settings.update { it.copy(autoSplit = false, trimSilence = false, homeTab = 1) }
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            device.wait(Until.hasObject(By.desc("Bắt đầu ghi âm")), 10_000)
            shot("01-home")

            device.findObject(By.desc("Bắt đầu ghi âm")).click()
            assertTrue("recording did not start", waitFor(10_000) { RecorderState.ui.value.active })
            assertTrue("no audio captured", waitFor(10_000) { RecorderState.ui.value.elapsedMs >= 2_000 })
            shot("02-recording")

            // Notification action path (same intent the lock-screen buttons send).
            RecordingService.command(context, RecordingService.ACTION_BOOKMARK)
            assertTrue(waitFor(5_000) { RecorderState.ui.value.bookmarks == 1 })

            device.openNotification()
            assertTrue(
                "recording notification with controls not shown",
                device.wait(Until.hasObject(By.text("● Đang ghi âm")), 5_000) &&
                    device.wait(Until.hasObject(By.text("Đánh dấu")), 5_000) &&
                    device.wait(Until.hasObject(By.text("Dừng")), 5_000),
            )
            shot("03-notification")
            device.pressBack()
            device.wait(Until.gone(By.text("● Đang ghi âm")), 3_000)

            // Screen off and on: the recording screen must come back over the lock screen, still recording.
            // Emulator images ship with the keyguard disabled; enable the swipe lock screen.
            device.executeShellCommand("locksettings set-disabled false")
            device.sleep()
            Thread.sleep(3_000)
            device.wakeUp()
            Thread.sleep(1_500)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            assertTrue("device should be locked after sleep/wake", keyguard.isKeyguardLocked)
            assertTrue(
                "recording controls must be visible over the lock screen",
                device.wait(Until.hasObject(By.desc("Dừng ghi âm")), 5_000),
            )
            shot("04-lockscreen")
            val elapsedBefore = RecorderState.ui.value.elapsedMs
            assertTrue(waitFor(5_000) { RecorderState.ui.value.elapsedMs > elapsedBefore + 1_000 })
            device.executeShellCommand("wm dismiss-keyguard")
            Thread.sleep(500)

            RecordingService.command(context, RecordingService.ACTION_TOGGLE_PAUSE)
            assertTrue(waitFor(5_000) { RecorderState.ui.value.paused })
            RecordingService.command(context, RecordingService.ACTION_TOGGLE_PAUSE)
            assertTrue(waitFor(5_000) { !RecorderState.ui.value.paused })

            val id = RecorderState.ui.value.sessionId
            assertNotNull(id)
            RecordingService.command(context, RecordingService.ACTION_STOP)
            assertTrue("recording did not stop", waitFor(15_000) { !RecorderState.ui.value.active })
            Thread.sleep(1_500)
            shot("05-detail")

            val session = MyRecapApp.from(context).store.get(id!!)
            assertNotNull("session was not saved", session)
            session!!
            assertEquals(RecState.STOPPED, session.state)
            assertEquals(1, session.bookmarksMs.size)
            assertTrue("expected audio segments", session.segments.isNotEmpty())
            assertTrue("duration ${session.durationMs}", session.durationMs >= 4_000)
            session.segments.forEach {
                val f = MyRecapApp.from(context).store.audioFile(session.id, it)
                assertTrue("missing ${f.name}", f.length() > 0)
            }

            device.pressBack()
            device.wait(Until.hasObject(By.desc("Bắt đầu ghi âm")), 5_000)
            shot("06-home-after")
        }
    }

    /** Seeds a folder like a real interview and walks the folder UI: clips, selection, summary. */
    @Test
    fun folderScreenSelectsClipsAndShowsSummaries() {
        val store = MyRecapApp.from(context).store
        val folder = store.create(SessionMode.INTERVIEW, "PV Backend – Nguyễn Văn A")
        val titles = listOf("Giới thiệu bản thân", "Kinh nghiệm Kotlin coroutines", "Lý do nghỉ việc")
        val now = System.currentTimeMillis()
        store.update(folder.id) { f ->
            f.copy(
                state = RecState.STOPPED,
                durationMs = 600_000,
                segments = titles.mapIndexed { i, t ->
                    Segment(
                        i, SessionStore.segmentFileName(i), i * 200_000L, 95_000, TaskStatus.DONE,
                        title = t, endMs = i * 200_000L + 120_000, recordedAt = now - (3 - i) * 200_000L,
                    )
                },
                bookmarksMs = listOf(30_000),
                summaries = listOf(
                    SummaryJob("demo", now, SessionMode.INTERVIEW, listOf(0, 1, 2), TaskStatus.DONE),
                ),
            )
        }
        // Placeholder audio so the folder shows its storage use and the clip reader its player.
        titles.indices.forEach { i -> store.audioFile(folder.id, store.get(folder.id)!!.segments[i]).writeBytes(ByteArray(180_000)) }
        titles.forEachIndexed { i, _ ->
            store.writeTranscript(
                folder.id, i,
                "Người phỏng vấn: Câu hỏi số ${i + 1}?\nỨng viên: Em đã làm việc 3 năm với Kotlin, chủ yếu là backend và Android, " +
                    "dùng coroutines cho các tác vụ mạng và cơ sở dữ liệu.",
            )
        }
        store.writeSummary(
            folder.id, "demo",
            "## Tổng quan\nỨng viên có **3 năm** kinh nghiệm Kotlin.\n## Điểm mạnh\n- Hiểu coroutines\n- Giao tiếp rõ ràng",
        )

        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_SESSION_ID, folder.id)
        ActivityScenario.launch<MainActivity>(launch).use {
            // Small emulator screen: only the first clip is guaranteed to be on screen.
            assertTrue("folder screen not shown", device.wait(Until.hasObject(By.text("Giới thiệu bản thân")), 10_000))
            shot("07-folder")

            // Summarize: one sheet to pick the template and the clips.
            device.findObject(By.text("Tóm tắt")).click()
            assertTrue("summarize sheet not shown", device.wait(Until.hasObject(By.text("Tóm tắt 3 đoạn")), 5_000))
            Thread.sleep(500)
            shot("08-summarize-sheet")
            device.pressBack()
            device.wait(Until.gone(By.text("Tóm tắt 3 đoạn")), 5_000)

            device.findObject(By.text("Tóm tắt · 1")).click()
            assertTrue("summary list not shown", device.wait(Until.hasObject(By.textContains("TỔNG QUAN")), 5_000))
            shot("09-summaries")
            device.findObject(By.text("Xem chi tiết")).click()
            assertTrue("summary reader not shown", device.wait(Until.hasObject(By.text("Tổng quan")), 5_000))
            shot("10-summary-reader")
            device.pressBack()

            assertTrue(device.wait(Until.hasObject(By.text("Đoạn hội thoại · 3")), 5_000))
            device.findObject(By.text("Đoạn hội thoại · 3")).click()
            device.wait(Until.hasObject(By.text("Giới thiệu bản thân")), 5_000)
            device.findObject(By.text("Giới thiệu bản thân")).click()
            assertTrue("clip reader not shown", device.wait(Until.hasObject(By.text("Đoạn 1 / 3")), 5_000))
            assertTrue("speaker labels shown", device.wait(Until.hasObject(By.text("Ứng viên")), 5_000))
            shot("11-clip-reader")
            device.pressBack()

            // One tap shares the whole folder (name, summary, transcripts) through the system sheet.
            assertTrue(device.wait(Until.hasObject(By.desc("Chia sẻ cả folder")), 5_000))
            device.findObject(By.desc("Chia sẻ cả folder")).click()
            assertTrue(
                "share sheet not shown",
                device.wait(Until.hasObject(By.pkg(Pattern.compile("com\\.android\\.intentresolver|android"))), 5_000),
            )
            Thread.sleep(1_000)
            shot("12-share-folder")
            device.pressBack()
            device.wait(Until.hasObject(By.desc("Tuỳ chọn folder")), 5_000)

            device.findObject(By.desc("Tuỳ chọn folder")).click()
            device.wait(Until.hasObject(By.text("Đổi tên")), 5_000)
            device.findObject(By.text("Đổi tên")).click()
            assertTrue("rename dialog not shown", device.wait(Until.hasObject(By.text("Đổi tên folder")), 5_000))
            shot("13-rename")
            device.findObject(By.text("Huỷ")).click()

            // Dark theme: the same screens must stay readable.
            device.executeShellCommand("cmd uimode night yes")
            Thread.sleep(2_500)
            shot("14-dark-folder")
            device.pressBack()
            Thread.sleep(1_200)
            shot("15-dark-home")
            device.executeShellCommand("cmd uimode night no")
            Thread.sleep(1_500)
        }
        store.delete(folder.id)
    }

    private companion object {
        const val SHOTS = "/data/local/tmp/myrecap-shots"
    }
}
