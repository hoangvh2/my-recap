package com.vh.myrecap

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.vh.myrecap.backup.BackupManager
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Recurrence
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.data.AppDatabase
import com.vh.myrecap.data.ItemStore
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.SessionStore
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId

/** Backup and restore, the database upgrade to repeating items, and the repeat UI. */
@RunWith(AndroidJUnit4::class)
class Phase3Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val app = MyRecapApp.from(context)
    private val created = mutableListOf<String>()

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
    }

    @After
    fun tearDown() {
        app.items.list().forEach { app.items.delete(it.id) }
        created.forEach { app.store.delete(it) }
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

    private fun scrollTo(selector: BySelector): UiObject2? {
        repeat(10) { attempt ->
            device.findObject(selector)?.let { return it }
            val w = device.displayWidth
            val h = device.displayHeight
            if (attempt < 6) device.swipe(w / 2, h * 3 / 4, w / 2, h / 3, 25) else device.swipe(w / 2, h / 3, w / 2, h * 3 / 4, 25)
            Thread.sleep(400)
        }
        return device.findObject(selector)
    }

    private fun folderWithAudio(): String {
        val store = app.store
        val folder = store.create(SessionMode.INTERVIEW, "PV sao lưu")
        created += folder.id
        val seg = Segment(0, SessionStore.segmentFileName(0), 0, 60_000, TaskStatus.DONE, title = "Giới thiệu", recordedAt = System.currentTimeMillis())
        store.update(folder.id) { it.copy(state = RecState.STOPPED, durationMs = 60_000, segments = listOf(seg)) }
        store.audioFile(folder.id, seg).writeBytes(ByteArray(50_000) { 7 })
        store.writeTranscript(folder.id, 0, "Ứng viên: Em có 5 năm kinh nghiệm.")
        return folder.id
    }

    @Test
    fun restoreMergesWithoutOverwritingNewerData() {
        val folderId = folderWithAudio()
        app.items.upsert(Item("keep", ItemType.TASK, ItemStatus.OPEN, "Bản gốc", createdAt = 1))
        app.items.upsert(Item("lost", ItemType.NOTE, ItemStatus.OPEN, "Sẽ bị xoá rồi khôi phục", createdAt = 2))

        val bytes = ByteArrayOutputStream().also { BackupManager(context).export(it, includeAudio = false) }.toByteArray()
        assertTrue(app.settings.current.lastBackupAt > 0)

        // After the backup: one item edited, one deleted, the folder deleted.
        app.items.update("keep") { it.copy(title = "Đã sửa sau khi sao lưu") }
        app.items.delete("lost")
        app.store.delete(folderId)

        val result = BackupManager(context).restore(ByteArrayInputStream(bytes))
        assertEquals(1, result.itemsAdded)
        assertEquals(1, result.itemsSkipped)
        assertEquals(1, result.sessionsAdded)
        assertEquals("newer edit kept", "Đã sửa sau khi sao lưu", app.items.get("keep")?.title)
        assertNotNull("deleted item restored", app.items.get("lost"))
        val folder = app.store.get(folderId)
        assertNotNull("folder restored", folder)
        assertEquals("Ứng viên: Em có 5 năm kinh nghiệm.", app.store.readTranscript(folderId, 0))
        assertFalse("audio was not in this backup", folder!!.segments.single().hasAudio)

        // Restoring the same file again adds nothing.
        val again = BackupManager(context).restore(ByteArrayInputStream(bytes))
        assertEquals(0, again.itemsAdded + again.sessionsAdded)
    }

    @Test
    fun backupWithAudioRestoresPlayableClips() {
        val folderId = folderWithAudio()
        val bytes = ByteArrayOutputStream().also { BackupManager(context).export(it, includeAudio = true) }.toByteArray()
        app.store.delete(folderId)
        BackupManager(context).restore(ByteArrayInputStream(bytes))
        val seg = app.store.get(folderId)!!.segments.single()
        assertTrue(seg.hasAudio)
        assertEquals(50_000L, app.store.audioFile(folderId, seg).length())
    }

    @Test
    fun databaseFromVersionOneUpgradesWithItsData() {
        val name = "upgrade-test.db"
        context.deleteDatabase(name)
        // The exact schema v0.2.38 created (Room, version 1).
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `items` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `details` TEXT NOT NULL, `whenAt` INTEGER, `allDay` INTEGER NOT NULL, `amount` INTEGER, " +
                    "`category` TEXT, `place` TEXT, `person` TEXT, `sourceId` TEXT, `quote` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`doneAt` INTEGER, `search` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            for (col in listOf("sourceId", "status", "whenAt", "type")) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_$col` ON `items` (`$col`)")
            }
            db.execSQL(
                "INSERT INTO items (id, type, status, title, details, whenAt, allDay, amount, category, place, person, sourceId, quote, createdAt, doneAt, search) " +
                    "VALUES ('old', 'TASK', 'OPEN', 'Việc cũ', '', NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, 5, NULL, 'viec cu')",
            )
            db.version = 1
        }
        val upgraded = AppDatabase.open(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = ItemStore(upgraded, scope)
            val old = store.get("old")
            assertEquals("Việc cũ", old?.title)
            assertNull(old?.recurrence)
            store.upsert(old!!.copy(recurrence = Recurrence.WEEKLY, whenAt = System.currentTimeMillis()))
            assertEquals(Recurrence.WEEKLY, store.get("old")?.recurrence)
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun completingARepeatingTaskBringsItBack() {
        val zone = ZoneId.systemDefault()
        val todayEight = LocalDate.now(zone).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        app.items.upsert(Item("pill", ItemType.TASK, ItemStatus.OPEN, "Uống thuốc", whenAt = todayEight, recurrence = Recurrence.DAILY, createdAt = 1))
        val (done, next) = app.items.complete("pill")!!
        assertEquals(ItemStatus.DONE, done.status)
        assertNotNull(next)
        assertEquals(ItemStatus.OPEN, next!!.status)
        assertEquals(Recurrence.DAILY, next.recurrence)
        assertTrue("next is later", next.whenAt!! > todayEight)
        assertEquals(2, app.items.list().size)
    }

    @Test
    fun repeatIsSetInTheEditorAndBackupSectionIsReachable() {
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        app.items.upsert(Item("standup", ItemType.EVENT, ItemStatus.OPEN, "Họp team đầu tuần", whenAt = at, createdAt = 1))
        app.settings.update { it.copy(homeTab = 0) }
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_ITEM_ID, "standup")
        ActivityScenario.launch<MainActivity>(launch).use {
            assertTrue("editor", device.wait(Until.hasObject(By.text("Lưu thay đổi")), 10_000))
            val weekly = scrollTo(By.text("Hằng tuần"))
            assertNotNull("repeat choices", weekly)
            weekly!!.click()
            Thread.sleep(400)
            shot("29-repeat-editor")
            device.findObject(By.text("Lưu thay đổi")).click()
            assertTrue("saved as weekly", waitFor(5_000) { app.items.get("standup")?.recurrence == Recurrence.WEEKLY })
            device.wait(Until.hasObject(By.text("Thư ký")), 5_000)
            assertNotNull("agenda shows it repeats", scrollTo(By.textContains("lặp hằng tuần")))
            shot("30-repeat-agenda")

            device.findObject(By.desc("Cài đặt")).click()
            assertTrue("settings opened", device.wait(Until.hasObject(By.text("1. Chuyển giọng nói → văn bản")), 5_000))
            shot("31a-settings-top")
            // Settings is one long page: swipe until the backup section shows.
            var backup: UiObject2? = null
            repeat(25) {
                if (backup == null) {
                    backup = device.findObject(By.text("Sao lưu ngay"))
                    if (backup == null) {
                        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
                        Thread.sleep(300)
                    }
                }
            }
            if (backup == null) shot("31b-settings-not-found")
            assertNotNull("backup section", backup)
            Thread.sleep(400)
            shot("31-backup-settings")
        }
    }

    private companion object {
        const val SHOTS = "/data/local/tmp/myrecap-shots"
    }
}
