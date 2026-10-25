package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAiTest {
    private fun note(
        title: String = "Quán bún chả",
        details: String = "Ngon, nhớ gọi nem",
        kind: String? = null,
        place: String? = null,
        attrs: Map<String, String> = emptyMap(),
        url: String? = null,
    ) = Item(id = "n", type = ItemType.NOTE, status = ItemStatus.OPEN, title = title, details = details, kind = kind, place = place, attrs = attrs, url = url)

    @Test
    fun readsAnAnswerEvenInsideAMarkdownFence() {
        val s = NoteAi.parse(
            "```json\n{\"kind\":\"place\",\"summary\":\"Bún chả ngon, gọi thêm nem.\",\"place\":\"24 Lê Văn Hưu\",\"values\":{\"dish\":\"bún chả\",\"channel\":\"x\",\"price\":\"60k\"}}\n```",
        )!!
        assertEquals(NoteKind.PLACE, s.kind)
        assertEquals("Bún chả ngon, gọi thêm nem.", s.summary)
        assertEquals("24 Lê Văn Hưu", s.place)
        assertEquals("only the place kind's own keys survive", mapOf("dish" to "bún chả", "price" to "60k"), s.attrs)
    }

    @Test
    fun unreadableOrEmptyAnswersAreNull() {
        assertNull(NoteAi.parse("xin lỗi, tôi không thể"))
        assertNull(NoteAi.parse("{not json}"))
        assertNull(NoteAi.parse("""{"kind":"null","summary":"","place":null,"values":{}}"""))
        assertNull("an unknown kind is nothing", NoteAi.parse("""{"kind":"recipe","summary":"","values":{}}"""))
    }

    @Test
    fun suggestionFillsOnlyWhatIsEmptyAndKeepsTheWritersWords() {
        val s = NoteAi.parse("""{"kind":"place","summary":"Bún chả ngon.","place":"24 Lê Văn Hưu","values":{"dish":"bún chả","price":"60k"}}""")!!
        val mine = note(attrs = mapOf("dish" to "nem"), place = "Hà Nội")
        val out = NoteAi.apply(mine, s)
        assertEquals("place", out.kind)
        assertEquals("my own dish and place stay", mapOf("dish" to "nem", "price" to "60k"), out.attrs)
        assertEquals("Hà Nội", out.place)
        assertEquals("Ý chính: Bún chả ngon.\n\nNgon, nhớ gọi nem", out.details)
        assertEquals("applying twice changes nothing", out, NoteAi.apply(out, s))
    }

    @Test
    fun theKindTheWriterChoseIsNotChangedAndItsValuesAreNotMixedIn() {
        val s = NoteAi.parse("""{"kind":"place","summary":"","place":null,"values":{"dish":"bún chả"}}""")!!
        val out = NoteAi.apply(note(kind = "read"), s)
        assertEquals("read", out.kind)
        assertTrue(out.attrs.isEmpty())
    }

    @Test
    fun theSummaryNeverPushesTheBodyPastTheCloudLimit() {
        val long = "x".repeat(CloudItem.MAX_DETAILS - 10)
        val s = NoteAi.Suggestion(null, "Ý chính dài hơn mười ký tự", null, emptyMap())
        assertEquals(long, NoteAi.apply(note(details = long), s).details)
        assertEquals("Ý chính: Ý chính dài hơn mười ký tự", NoteAi.apply(note(details = ""), s).details)
    }

    @Test
    fun inputHasTheNoteTextAndIsCut() {
        val text = NoteAi.input(note(url = "https://a.vn/1", place = "5 Lê Lợi"))
        assertTrue(text.contains("Tiêu đề: Quán bún chả") && text.contains("Địa chỉ: 5 Lê Lợi") && text.contains("Link: https://a.vn/1"))
        assertEquals(NoteAi.MAX_INPUT, NoteAi.input(note(details = "y".repeat(10_000))).length)
        assertTrue(NoteAi.userMessage(note()).contains("place|read|video|null"))
    }

    @Test
    fun previewListsEverythingUseWouldAdd() {
        val s = NoteAi.parse("""{"kind":"place","summary":"Bún chả ngon.","place":"24 Lê Văn Hưu","values":{"dish":"bún chả"}}""")!!
        assertEquals("Loại: 📍 Địa điểm\nÝ chính: Bún chả ngon.\nĐịa chỉ: 24 Lê Văn Hưu\nMón / dịch vụ: bún chả", NoteAi.preview(s))
    }
}
