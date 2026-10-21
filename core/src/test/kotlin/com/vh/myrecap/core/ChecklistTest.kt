package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChecklistTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(d: Int, h: Int = 12, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun item(texts: List<String> = emptyList(), type: ItemType = ItemType.TASK, recurrence: Recurrence? = null, whenAt: Long? = null) =
        Item(id = "a", type = type, status = ItemStatus.OPEN, title = "Ra khỏi nhà", recurrence = recurrence, whenAt = whenAt, checklist = Checklist.entriesFrom(texts))

    @Test
    fun parsesLinesFromPasteOrCommasDroppingBulletsBlanksAndRepeats() {
        assertEquals(listOf("ví", "chìa khoá", "sạc"), Checklist.parseLines("ví, chìa khoá;  sạc , Ví"))
        assertEquals(listOf("Ví", "Chìa khoá", "Sạc"), Checklist.parseLines("- Ví\n[ ] Chìa khoá\n\n* Sạc\nví"))
        assertEquals(listOf("Ví, ghi chú"), Checklist.parseLines("Ví, ghi chú", splitCommas = false))
        assertEquals(40, Checklist.parseLines((0 until 60).joinToString("\n") { "m$it" }).size)
    }

    @Test
    fun showsOnlyWhatIsMissingAtMostThreeThenACount() {
        val i = item(listOf("Ví", "Chìa khoá", "Sạc", "Thuốc", "Thẻ xe"))
        assertEquals("Còn: Ví · Chìa khoá · Sạc · +2", Checklist.remainingLabel(i))
        assertEquals("0/5", Checklist.chipLabel(i))
        val t = Checklist.toggle(Checklist.toggle(i, i.checklist[0].id), i.checklist[3].id)
        assertEquals("Còn: Chìa khoá · Sạc · Thẻ xe", Checklist.remainingLabel(t))
        assertEquals("2/5", Checklist.chipLabel(t))
        assertFalse(Checklist.progress(t).complete)
    }

    @Test
    fun tickingEverythingSaysSoInWordsAndLeavesNothingToShow() {
        val i = Checklist.tickAll(item(listOf("Ví", "Sạc")))
        assertTrue(Checklist.progress(i).complete)
        assertEquals("Đủ 2/2", Checklist.chipLabel(i))
        assertEquals("", Checklist.remainingLabel(i))
    }

    @Test
    fun toggleIsReversibleAndUnknownIdChangesNothing() {
        val i = item(listOf("Ví"))
        val id = i.checklist[0].id
        assertEquals(i, Checklist.toggle(Checklist.toggle(i, id), id))
        assertEquals(i, Checklist.toggle(i, "nope"))
    }

    @Test
    fun onlyTasksAndAppointmentsHaveAChecklist() {
        assertEquals(0, Checklist.progress(item(listOf("Ví"), type = ItemType.NOTE)).total)
        assertNull(CloudItem.toMap(item(listOf("Ví"), type = ItemType.NOTE))["checklist"])
    }

    @Test
    fun appendSkipsLinesAlreadyThereAndStopsAtTheLimit() {
        val l = Checklist.entriesFrom(listOf("Ví"))
        assertEquals(listOf("Ví", "Sạc"), Checklist.append(l, listOf("ví", "Sạc")).map { it.text })
        assertTrue(Checklist.append(l, listOf("VÍ")) === l)
        assertEquals(40, Checklist.append(emptyList(), (0 until 50).map { "m$it" }).size)
    }

    @Test
    fun aQuickLineWithAColonAndTwoLinesCarriesAChecklistTimesAndShortOnesDoNot() {
        assertEquals("ra khỏi nhà 7h30 hằng ngày" to listOf("ví", "chìa khoá", "sạc"), Checklist.splitLine("ra khỏi nhà 7h30 hằng ngày: ví, chìa khoá, sạc"))
        assertNull(Checklist.splitLine("họp 9:30, mang laptop, báo giá"))
        assertNull(Checklist.splitLine("gọi anh Nam: mai 9h"))
        assertNull(Checklist.splitLine("cà phê 45k"))
        assertNull(Checklist.splitLine(": ví, sạc"))
    }

    @Test
    fun survivesJsonAndTheCloudAndIsRemovedWhenEmptied() {
        val i = Checklist.toggle(item(listOf("Ví", "Sạc")), item(listOf("Ví")).checklist[0].id)
        val ticked = i.copy(checklist = i.checklist.mapIndexed { n, e -> e.copy(done = n == 0) })
        assertEquals(ticked.checklist, Item.fromJson(ticked.toJson()).checklist)
        assertFalse(item().toJson().has("checklist"))
        val map = CloudItem.toMap(ticked)
        assertEquals(ticked.checklist, CloudItem.fromMap("a", map)!!.checklist)
        assertNull("emptied checklist is written as a removal", CloudItem.toMap(ticked.copy(checklist = emptyList()))["checklist"])
        assertTrue(CloudItem.sameInCloud(ticked, CloudItem.fromMap("a", map)!!))
    }

    @Test
    fun aRepeatingItemStartsEachOccurrenceUnticked() {
        val done = Checklist.tickAll(item(listOf("Ví", "Sạc"), recurrence = Recurrence.DAILY, whenAt = ms(14, 7, 30)))
        val next = Schedule.nextInstance(done.copy(status = ItemStatus.DONE), "n", zone, ms(14, 7, 40))!!
        assertEquals(ms(15, 7, 30), next.whenAt)
        assertEquals(0, Checklist.progress(next).done)
        assertTrue(Checklist.progress(done).complete)

        val ev = Checklist.tickAll(item(listOf("Báo giá"), ItemType.EVENT, Recurrence.WEEKLY, ms(14, 9)))
        assertEquals(0, Checklist.progress(Schedule.finishEvent(ev, zone, ms(14, 9, 30))).done)
        val passed = item(listOf("Báo giá"), ItemType.EVENT, Recurrence.DAILY, ms(14, 9))
        val moved = Schedule.rollForward(Checklist.tickAll(passed), zone, ms(14, 11))!!
        assertEquals(ms(15, 9), moved.whenAt)
        assertEquals(0, Checklist.progress(moved).done)
    }

    // ---- templates

    private val defaults = ChecklistTemplates.DEFAULTS

    @Test
    fun validatesNameLinesAndTimeAndRefusesADuplicateName() {
        fun check(name: String = "Đi công tác", lines: String = "Vé\nSạc\nSạc", type: ItemType = ItemType.TASK, rec: Recurrence? = null, time: String? = null, self: String? = null) =
            ChecklistTemplates.validate(defaults, name, lines, type, rec, time, self)
        assertEquals("Cần nhập tên mẫu", check(name = " ").error)
        assertEquals("Đã có mẫu trùng tên", check(name = "ra khoi nha").error)
        assertEquals("Cần ít nhất một mục (mỗi dòng một mục)", check(lines = " \n ").error)
        assertEquals("Giờ chưa đúng", check(time = "25:00").error)
        assertEquals("Lịch hẹn lặp cần có giờ", check(type = ItemType.EVENT, rec = Recurrence.DAILY).error)
        val ok = check().template!!
        assertEquals(listOf("Vé", "Sạc"), ok.items)
        assertNull(check(name = "Ra khỏi nhà", self = "default-leave-home").error)
    }

    @Test
    fun addUpdateRemoveRestoreAndRestoreBuiltIns() {
        val t = ChecklistTemplates.validate(defaults, "Đi công tác", "Vé\nSạc", ItemType.TASK, null, null).template!!
        val added = ChecklistTemplates.add(defaults, t)
        assertEquals(4, added.size)
        assertEquals("Công tác", ChecklistTemplates.update(added, t.copy(name = "Công tác")).last().name)
        val gone = ChecklistTemplates.remove(added, "default-delivery")
        assertFalse(gone.any { it.name == "Giao hàng cho khách" })
        assertEquals(added.size - 1, gone.size)
        assertTrue(ChecklistTemplates.restoreDefaults(gone).any { it.name == "Giao hàng cho khách" })
        assertEquals(defaults, ChecklistTemplates.restoreDefaults(defaults))
        assertEquals(added, ChecklistTemplates.restore(gone, 1, added[1]))
    }

    @Test
    fun offersTheMostUsedFirstAndFindsByWordsWithoutAccents() {
        val used = ChecklistTemplates.used(defaults, "default-meeting", 5)
        assertEquals("Chuẩn bị họp", ChecklistTemplates.byUse(used).first().name)
        assertEquals(listOf("Giao hàng cho khách"), ChecklistTemplates.matching(defaults, "giao").map { it.name })
        assertEquals(listOf("Chuẩn bị họp"), ChecklistTemplates.matching(defaults, "chuan bi").map { it.name })
        assertTrue(ChecklistTemplates.matching(defaults, "").isEmpty())
    }

    @Test
    fun aDailyTemplateStartsTodayWhenAheadElseTomorrowNeverOverdue() {
        val leave = defaults[0]
        val early = ChecklistTemplates.instantiate(leave, "x", ms(21, 6), zone)
        assertEquals(ms(21, 7, 30), early.whenAt)
        assertEquals(Recurrence.DAILY, early.recurrence)
        assertFalse(early.allDay)
        assertEquals(5, Checklist.progress(early).total)
        assertEquals(ms(22, 7, 30), ChecklistTemplates.instantiate(leave, "x", ms(21, 10), zone).whenAt)
        assertEquals("Việc · Hằng ngày · 07:30", ChecklistTemplates.scheduleLabel(leave))
    }

    @Test
    fun aTemplateWithoutTimeOrRepeatIsJustTheBaseForTheEditor() {
        val i = ChecklistTemplates.instantiate(defaults[1], "x", ms(21, 10), zone)
        assertNull(i.whenAt)
        assertNull(i.recurrence)
        assertEquals(4, i.checklist.size)
        assertNotNull(ChecklistTemplates.instantiate(defaults[2], "x", ms(21, 10), zone).whenAt)
    }

    @Test
    fun templatesSurviveStorageAndABrokenValueReadsAsEmpty() {
        assertEquals(defaults, ChecklistTemplates.decode(ChecklistTemplates.encode(defaults)))
        assertNull(ChecklistTemplates.decode(null))
        assertEquals(emptyList<ChecklistTemplate>(), ChecklistTemplates.decode("not json"))
    }
}
