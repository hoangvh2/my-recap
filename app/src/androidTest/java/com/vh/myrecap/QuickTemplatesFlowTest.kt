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
import com.vh.myrecap.core.QuickTemplate
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

/** Saved quick-add lines: one per kind on the empty box, more as the user types, and their own screen. */
@RunWith(AndroidJUnit4::class)
class QuickTemplatesFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private var saved: List<QuickTemplate> = emptyList()

    @get:Rule
    val failureShot = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = shot("fail-${description.methodName}")
    }

    @Before
    fun setUp() {
        device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        device.executeShellCommand("wm dismiss-keyguard")
        app.items.list().forEach { app.items.delete(it.id) }
        saved = app.settings.current.quickTemplates
        app.settings.update {
            it.copy(
                homeTab = 0,
                quickTemplates = listOf(
                    QuickTemplate("cà phê 30k", uses = 12),
                    QuickTemplate("cafe với khách …k", uses = 2),
                    QuickTemplate("ăn trưa …k", uses = 6),
                    QuickTemplate("gọi … mai 9h", uses = 4),
                    QuickTemplate("gửi báo cáo tuần", uses = 1),
                    QuickTemplate("họp … thứ 2 9h", uses = 3),
                    QuickTemplate("đổ xăng …k"),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        app.items.list().forEach { app.items.delete(it.id) }
        app.settings.update { it.copy(quickTemplates = saved) }
    }

    @Test
    fun boxOffersOnePerKindThenMatchesAndOwnScreen() {
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            assertTrue("agenda", device.wait(Until.hasObject(By.text("Thư ký")), 10_000))
            // Not focused: no extra row, the agenda keeps its place.
            assertFalse(device.hasObject(By.text("cà phê 30k")))
            device.findObject(By.clazz("android.widget.EditText")).click()
            // The most used line of each kind, nothing else.
            assertTrue("featured", device.wait(Until.hasObject(By.text("cà phê 30k")), 5_000))
            assertTrue(device.hasObject(By.text("gọi … mai 9h")))
            assertTrue(device.hasObject(By.text("họp … thứ 2 9h")))
            assertFalse("only one expense line", device.hasObject(By.text("ăn trưa …k")))
            shot("40-quickadd-featured")

            device.findObject(By.clazz("android.widget.EditText")).text = "ca"
            assertTrue("matches", device.wait(Until.hasObject(By.text("cafe với khách …k")), 5_000))
            assertTrue(device.hasObject(By.text("cà phê 30k")))
            shot("41-quickadd-matching")

            // One tap on a line without a blank adds it.
            device.findObject(By.text("cà phê 30k")).click()
            assertTrue("expense added", waitFor(5_000) { app.items.list().any { it.type == ItemType.EXPENSE && it.amount == 30_000L } })
            Thread.sleep(400)
            shot("42-quickadd-added")

            device.findObject(By.desc("Mẫu câu")).click()
            assertTrue("templates screen", device.wait(Until.hasObject(By.text("Mẫu câu nhập nhanh")), 5_000))
            assertTrue(device.wait(Until.hasObject(By.text("Hiện sẵn ở ô gõ · Dùng 13 lần")), 5_000))
            shot("43-templates-screen")

            device.findObject(By.clazz("android.widget.EditText")).text = "goi"
            assertTrue(device.wait(Until.hasObject(By.text("gọi … mai 9h")), 5_000))
            assertFalse(device.hasObject(By.text("cà phê 30k")))
            shot("44-templates-search")

            device.findObject(By.clazz("android.widget.EditText")).text = "gửi xe 5k"
            assertTrue(device.wait(Until.hasObject(By.text("Lưu mẫu")), 5_000))
            shot("45-templates-new")
            device.findObject(By.text("Lưu mẫu")).click()
            assertTrue("saved", waitFor(5_000) { app.settings.current.quickTemplates.any { it.text == "gửi xe 5k" } })

            device.findObject(By.text("gọi … mai 9h")).click()
            assertTrue("edit dialog", device.wait(Until.hasObject(By.text("Sửa mẫu")), 5_000))
            shot("46-template-edit")
            device.findObject(By.text("Huỷ")).click()
        }
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

    private companion object {
        const val SHOTS = "/data/local/tmp/myrecap-shots"
    }
}
