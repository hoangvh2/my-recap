package com.vh.myrecap

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vh.myrecap.core.Holidays
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Lunar
import com.vh.myrecap.core.LunarPrefs
import com.vh.myrecap.core.Recurrence
import com.vh.myrecap.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * Lunar calendar and holidays: the schedule is packaged, the month grid opens from the calendar tab, a lunar repeat
 * shows its lunar date in the editor, and deleting it asks first ("Huỷ" keeps it, "Xoá" removes it).
 */
@RunWith(AndroidJUnit4::class)
class LunarFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private var savedLunar = LunarPrefs()

    @get:Rule
    val failureShot = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            device.executeShellCommand("mkdir -p $SHOTS")
            device.executeShellCommand("screencap -p $SHOTS/fail-${description.methodName}.png")
        }
    }

    @Before
    fun setUp() {
        device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        device.executeShellCommand("wm dismiss-keyguard")
        savedLunar = app.settings.current.lunar
        app.settings.update { it.copy(homeTab = 0, lunar = LunarPrefs()) }
        app.items.list().forEach { app.items.delete(it.id) }
    }

    @After
    fun tearDown() {
        app.items.list().forEach { app.items.delete(it.id) }
        app.settings.update { it.copy(lunar = savedLunar) }
    }

    @Test
    fun scheduleIsPackagedAndMonthGridOpens() {
        assertTrue("bundled holiday schedule", Holidays.bundled().isNotEmpty())
        assertEquals(LocalDate.of(2027, 2, 6), Lunar.toSolar(2027, 1, 1))

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            assertTrue("agenda", device.wait(Until.hasObject(By.desc("Cài đặt")), 10_000))
            // The lunar date sits next to today's date.
            assertTrue("lunar date in the header", device.wait(Until.hasObject(By.textContains(" Âm")), 5_000))
            device.findObject(By.text("Lịch hẹn")).click()
            assertTrue("view switch", device.wait(Until.hasObject(By.text("Tháng")), 5_000))
            device.findObject(By.text("Tháng")).click()
            val now = LocalDate.now(ZoneId.systemDefault())
            assertTrue("month title", device.wait(Until.hasObject(By.text("Tháng ${now.monthValue}/${now.year}")), 5_000))
            shot("60-lunar-month")
            device.findObject(By.desc("Tháng sau")).click()
            val next = now.plusMonths(1)
            assertTrue("next month", device.wait(Until.hasObject(By.text("Tháng ${next.monthValue}/${next.year}")), 5_000))
        }
    }

    @Test
    fun lunarRepeatShowsItsLunarDateAndDeleteAsksFirst() {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2027, 4, 26) // 20/3 Âm
        val at = day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        app.items.upsert(Item("gio-ong", ItemType.EVENT, ItemStatus.OPEN, "Giỗ ông nội", whenAt = at, recurrence = Recurrence.LUNAR_YEARLY, createdAt = 1))

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ITEM_ID, "gio-ong")).use {
            assertTrue("editor", device.wait(Until.hasObject(By.text("Lưu thay đổi")), 10_000))
            assertTrue("lunar date under the date", device.wait(Until.hasObject(By.text("= 20 tháng 3 Âm")), 5_000))
            shot("61-lunar-editor")

            // "Huỷ": the item stays and the dialog says it takes the later years with it.
            device.findObject(By.desc("Xoá mục")).click()
            assertTrue("dialog", device.wait(Until.hasObject(By.text("Xoá mục này?")), 5_000))
            assertTrue("repeat warning", device.hasObject(By.textContains("Xoá sẽ bỏ cả các lần sau")))
            shot("62-lunar-delete-confirm")
            device.findObject(By.text("Huỷ")).click()
            assertTrue(device.wait(Until.gone(By.text("Xoá mục này?")), 5_000))
            assertNotNull("kept after Huỷ", app.items.get("gio-ong"))

            // "Xoá": it goes.
            device.findObject(By.desc("Xoá mục")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Xoá mục này?")), 5_000))
            device.findObject(By.text("Xoá")).click()
            assertTrue("deleted", waitFor(5_000) { app.items.get("gio-ong") == null })
        }
        assertNull(app.items.get("gio-ong"))
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
