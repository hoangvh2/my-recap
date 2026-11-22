package com.vh.myrecap

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vh.myrecap.core.CompanyHoliday
import com.vh.myrecap.core.LunarPrefs
import com.vh.myrecap.ui.MainActivity
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Company holidays: listed under Settings, and deleting one asks first ("Huỷ" keeps it, "Xoá" removes it). */
@RunWith(AndroidJUnit4::class)
class CompanyHolidayFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private var savedLunar = LunarPrefs()

    @Before
    fun setUp() {
        device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        device.executeShellCommand("wm dismiss-keyguard")
        savedLunar = app.settings.current.lunar
        val year = LocalDate.now().year
        val summer = CompanyHoliday("t1", "Nghỉ hè công ty", LocalDate.of(year, 7, 20), LocalDate.of(year, 7, 22), true)
        app.settings.update { it.copy(homeTab = 0, lunar = LunarPrefs(company = listOf(summer))) }
    }

    @After
    fun tearDown() {
        app.settings.update { it.copy(lunar = savedLunar) }
    }

    @Test
    fun deleteAsksFirst() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            assertTrue("agenda", device.wait(Until.hasObject(By.desc("Cài đặt")), 10_000))
            device.findObject(By.desc("Cài đặt")).click()
            assertTrue("company section", device.wait(Until.hasObject(By.desc("Xoá kỳ nghỉ Nghỉ hè công ty")), 10_000) || scrollTo("Xoá kỳ nghỉ Nghỉ hè công ty"))
            shot("70-company-settings")

            // "Huỷ": it stays.
            device.findObject(By.desc("Xoá kỳ nghỉ Nghỉ hè công ty")).click()
            assertTrue("dialog", device.wait(Until.hasObject(By.text("Xoá kỳ nghỉ này?")), 5_000))
            shot("71-company-delete-confirm")
            device.findObject(By.text("Huỷ")).click()
            assertTrue(device.wait(Until.gone(By.text("Xoá kỳ nghỉ này?")), 5_000))
            assertEquals(1, app.settings.current.lunar.company.size)

            // "Xoá": it goes.
            device.findObject(By.desc("Xoá kỳ nghỉ Nghỉ hè công ty")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Xoá kỳ nghỉ này?")), 5_000))
            device.findObject(By.text("Xoá")).click()
            assertTrue("deleted", waitFor(5_000) { app.settings.current.lunar.company.isEmpty() })
        }
    }

    private fun scrollTo(desc: String): Boolean {
        repeat(25) {
            if (device.hasObject(By.desc(desc))) return true
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        }
        return device.hasObject(By.desc(desc))
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
