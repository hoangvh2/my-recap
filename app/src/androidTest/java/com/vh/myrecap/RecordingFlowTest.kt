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
import com.vh.myrecap.data.RecState
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.recorder.RecordingService
import com.vh.myrecap.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            device.wait(Until.hasObject(By.text("GHI ÂM")), 10_000)
            shot("01-home")

            device.findObject(By.text("GHI ÂM")).click()
            assertTrue("recording did not start", waitFor(10_000) { RecorderState.ui.value.active })
            assertTrue("no audio captured", waitFor(10_000) { RecorderState.ui.value.elapsedMs >= 2_000 })
            shot("02-recording")

            // Notification action path (same intent the lock-screen buttons send).
            RecordingService.command(context, RecordingService.ACTION_BOOKMARK)
            assertTrue(waitFor(5_000) { RecorderState.ui.value.bookmarks == 1 })

            device.openNotification()
            device.wait(Until.hasObject(By.textContains("Đánh dấu")), 5_000)
            shot("03-notification")
            device.pressBack()

            // Screen off and on: the recording screen must come back over the lock screen, still recording.
            device.sleep()
            Thread.sleep(1_500)
            device.wakeUp()
            Thread.sleep(1_500)
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
            device.wait(Until.hasObject(By.text("GHI ÂM")), 5_000)
            shot("06-home-after")
        }
    }

    private companion object {
        const val SHOTS = "/data/local/tmp/myrecap-shots"
    }
}
