package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ItemTagsTest {
    private fun item(
        type: ItemType = ItemType.TASK,
        title: String = "Nộp báo cáo",
        tag: String? = null,
        person: String? = null,
        place: String? = null,
        status: ItemStatus = ItemStatus.OPEN,
    ) = Item(id = "a", type = type, status = status, title = title, tag = tag, person = person, place = place)

    @Test
    fun tagSurvivesJsonAndUnknownKeysAreDropped() {
        val back = Item.fromJson(item(tag = "red").toJson())
        assertEquals("red", back.tag)
        assertEquals(ItemTag.RED, back.itemTag)
        assertNull(Item.fromJson(item(tag = "magenta").toJson()).tag)
        assertFalse(item().toJson().has("tag"))
    }

    @Test
    fun tagTravelsToTheCloudAndBackAndIsRemovedWhenCleared() {
        val tagged = item(type = ItemType.EVENT, tag = "purple")
        val map = CloudItem.toMap(tagged)
        assertEquals("purple", map["tag"])
        assertEquals("purple", CloudItem.fromMap("a", map)!!.tag)
        assertNull("a cleared tag is written as a removal", CloudItem.toMap(tagged.copy(tag = null))["tag"])
        assertNull(CloudItem.toMap(item(type = ItemType.NOTE, tag = "red"))["tag"])
        assertNull(CloudItem.fromMap("a", map + ("tag" to "magenta"))!!.tag)
    }

    @Test
    fun everyTagHasItsOwnGlyphAndKey() {
        assertEquals(ItemTag.entries.size, ItemTag.entries.map { it.glyph }.toSet().size)
        assertEquals(ItemTag.entries.size, ItemTag.entries.map { it.key }.toSet().size)
    }

    @Test
    fun filterByTagAnyTagAndAvailability() {
        val items = listOf(item(tag = "red"), item(tag = "blue"), item(), item(tag = "purple", status = ItemStatus.DONE))
        assertEquals(4, TagFilter().apply(items).size)
        assertEquals(3, TagFilter(anyTag = true).apply(items).size)
        assertEquals(1, TagFilter(tag = ItemTag.BLUE).apply(items).size)
        assertEquals(listOf(ItemTag.RED, ItemTag.BLUE), TagFilter.available(items))
    }

    @Test
    fun rulesMatchIgnoringCaseAndAccentsFirstWins() {
        val rules = listOf(
            TagRule(TagRule.Field.PERSON, "nguyễn", ItemTag.PURPLE),
            TagRule(TagRule.Field.KEYWORD, "BÁO CÁO", ItemTag.RED),
            TagRule(TagRule.Field.PLACE, "trường", ItemTag.ORANGE),
        )
        assertEquals(ItemTag.RED, TagRules.match(rules, item()))
        assertEquals(ItemTag.PURPLE, TagRules.match(rules, item(person = "Anh NGUYEN", title = "Nộp báo cáo")))
        assertEquals(ItemTag.ORANGE, TagRules.match(rules, item(type = ItemType.EVENT, title = "Họp", place = "Trường Lê Quý Đôn")))
        assertNull(TagRules.match(rules, item(title = "Mua sữa")))
        assertNull("notes and expenses are never tagged", TagRules.match(rules, item(type = ItemType.NOTE)))
    }

    @Test
    fun rulesApplyOnlyToNewItemsWithoutATag() {
        val rules = listOf(TagRule(TagRule.Field.KEYWORD, "báo cáo", ItemTag.RED))
        assertEquals("red", TagRules.applyToNew(rules, item()).tag)
        assertEquals("blue", TagRules.applyToNew(rules, item(tag = "blue")).tag)
        assertNull(TagRules.applyToNew(emptyList(), item()).tag)
    }

    @Test
    fun rulesRoundTripAndCapAndDedupe() {
        val rules = listOf(TagRule(TagRule.Field.PERSON, "Nam", ItemTag.BLUE))
        assertEquals(rules, TagRules.decode(TagRules.encode(rules)))
        assertNull(TagRules.decode("not json"))
        assertEquals(rules, TagRules.add(rules, TagRule(TagRule.Field.PERSON, " nam ", ItemTag.RED)))
        assertEquals(rules, TagRules.add(rules, TagRule(TagRule.Field.KEYWORD, "  ", ItemTag.RED)))
        var full = emptyList<TagRule>()
        repeat(30) { full = TagRules.add(full, TagRule(TagRule.Field.KEYWORD, "k$it", ItemTag.RED)) }
        assertEquals(TagRules.MAX, full.size)
    }

    @Test
    fun repeatsStayOutOfTheNightWhenAsked() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val important = item().copy(important = true)
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 10, h, m, 0, 0, zone).toInstant().toEpochMilli()
        val interval = ReminderPolicy.NAG_INTERVAL_MS
        assertEquals(at(14, 10), ReminderPolicy.nextNagAt(important, 0, at(14, 0), quiet = true, zone = zone))
        // 21:55 + 10 min = 22:05 → next morning 06:30
        assertEquals(ZonedDateTime.of(2026, 10, 11, 6, 30, 0, 0, zone).toInstant().toEpochMilli(), ReminderPolicy.nextNagAt(important, 0, at(21, 55), quiet = true, zone = zone))
        // 02:00 + 10 min → same day 06:30
        assertEquals(at(6, 30), ReminderPolicy.nextNagAt(important, 0, at(2, 0), quiet = true, zone = zone))
        assertEquals(at(2, 0) + interval, ReminderPolicy.nextNagAt(important, 0, at(2, 0), quiet = false, zone = zone))
        assertTrue(ReminderPolicy.nextNagAt(important, 0, at(14, 0), quiet = true, zone = zone)!! > at(14, 0))
    }

    @Test
    fun studyTagIsTheStudyLaneAndGoesLast() {
        val work = item(tag = "red").copy(id = "w1")
        val study = item(tag = "green").copy(id = "s1")
        val plain = item().copy(id = "w2")
        assertEquals(ItemTag.GREEN, study.itemTag)
        assertTrue(study.isStudy)
        assertFalse(work.isStudy)
        assertEquals(listOf("w1", "w2", "s1"), StudyLane.studyLast(listOf(study, work, plain)).map { it.id })
    }

    @Test
    fun studyLaneCountsTodayAndMissedBlocksOnly() {
        val zone = java.time.ZoneId.of("UTC")
        val day = java.time.LocalDate.of(2026, 11, 2)
        fun at(d: java.time.LocalDate) = d.atTime(7, 30).atZone(zone).toInstant().toEpochMilli()
        val a = item(tag = "green").copy(id = "a", whenAt = at(day))
        val missed = item(tag = "green").copy(id = "m", whenAt = at(day.minusDays(1)))
        val later = item(tag = "green").copy(id = "l", whenAt = at(day.plusDays(1)))
        assertEquals(listOf("a", "m"), StudyLane.today(listOf(a, missed, later), day, zone).map { it.id })
    }

    @Test
    fun studyLaneAddsUpTheMinutesStillPlanned() {
        val i = item(tag = "green").copy(checklist = listOf(ChecklistEntry("1", "10 phút: Anki", false), ChecklistEntry("2", "25 phút: Voca", false), ChecklistEntry("3", "5 phút: xong rồi", true)))
        assertEquals(StudyLane.Summary(1, 35), StudyLane.summary(listOf(i)))
    }

    private fun block(id: String, at: Long, minutes: Int = 25) =
        item(tag = "green").copy(id = id, whenAt = at, checklist = listOf(ChecklistEntry("c$id", "$minutes phút: học")))

    @Test
    fun focusIsDueFromTenMinutesBeforeUntilAQuarterHourAfterTheBlockEnds() {
        val min = 60_000L
        val start = 1_000_000 * min
        val b = block("b", start)
        assertEquals(StudyLane.Focus(b, false), StudyLane.focus(listOf(b), start - 11 * min))
        assertEquals(StudyLane.Focus(b, true), StudyLane.focus(listOf(b), start - 10 * min))
        assertEquals(StudyLane.Focus(b, true), StudyLane.focus(listOf(b), start + 40 * min))
        assertNull(StudyLane.focus(listOf(b), start + 41 * min))
    }

    @Test
    fun focusPicksTheNearestStartAndSkipsBlocksThatCannotStart() {
        val min = 60_000L
        val now = 1_000_000 * min
        val soon = block("soon", now + 5 * min)
        val later = block("later", now + 120 * min)
        val untimed = item(tag = "green").copy(id = "u", whenAt = now, checklist = listOf(ChecklistEntry("x", "đọc sách")))
        assertEquals("soon", StudyLane.focus(listOf(later, untimed, soon), now)!!.item.id)
        assertEquals("later", StudyLane.focus(listOf(later, untimed), now)!!.item.id)
        assertNull(StudyLane.focus(listOf(untimed), now))
    }
}
