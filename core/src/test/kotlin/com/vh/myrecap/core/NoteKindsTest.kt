package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteKindsTest {
    private fun note(
        kind: String? = "place",
        url: String? = "https://maps.app.goo.gl/abc",
        attrs: Map<String, String> = mapOf("dish" to "bún chả", "visited" to "1"),
        type: ItemType = ItemType.NOTE,
    ) = Item(id = "n1", type = type, status = ItemStatus.OPEN, title = "Quán Hương Liên", kind = kind, url = url, attrs = attrs)

    @Test
    fun kindLinkAndValuesSurviveJson() {
        val back = Item.fromJson(note().toJson())
        assertEquals(NoteKind.PLACE, back.noteKind)
        assertEquals("https://maps.app.goo.gl/abc", back.url)
        assertEquals(mapOf("dish" to "bún chả", "visited" to "1"), back.attrs)
    }

    @Test
    fun plainNoteWritesNoNewFieldsAndUnknownKindReadsAsPlain() {
        val json = note(kind = null, url = null, attrs = emptyMap()).toJson()
        assertFalse(json.has("kind") || json.has("url") || json.has("attrs"))
        assertNull(Item.fromJson(note(kind = "recipe").toJson()).kind)
    }

    @Test
    fun cleanKeepsOnlyTheKindsOwnValuesAndClipsThem() {
        val cleaned = NoteKinds.clean(note(attrs = mapOf("dish" to "  ${"x".repeat(300)} ", "channel" to "ABC", "visited" to "yes", "price" to " ")))
        assertEquals(setOf("dish", "visited"), cleaned.attrs.keys)
        assertEquals(200, cleaned.attrs["dish"]!!.length)
        assertEquals("1", cleaned.attrs["visited"])
    }

    @Test
    fun onlyNotesKeepAKind() {
        val cleaned = NoteKinds.clean(note(type = ItemType.TASK))
        assertNull(cleaned.kind)
        assertNull(cleaned.url)
        assertTrue(cleaned.attrs.isEmpty())
        assertEquals(500, NoteKinds.clean(note(url = "h".repeat(900))).url!!.length)
    }

    @Test
    fun travelsToTheCloudAndBackAndIsRemovedWhenCleared() {
        val map = CloudItem.toMap(note())
        assertEquals("place", map["kind"])
        val back = CloudItem.fromMap("n1", map)!!
        assertEquals(note().attrs, back.attrs)
        assertNull(CloudItem.toMap(note(kind = null, url = null, attrs = emptyMap()))["kind"])
        assertNull(CloudItem.fromMap("n1", map + ("kind" to "recipe"))!!.kind)
        assertNull(CloudItem.toMap(note(type = ItemType.TASK))["kind"])
    }

    @Test
    fun doneFlagFollowsTheKind() {
        assertTrue(note().noteDone)
        assertFalse(note(attrs = emptyMap()).noteDone)
        assertEquals("read", NoteKind.READ.doneFlag)
    }

    @Test
    fun mapsButtonUsesTheAddressAndOnlyWebLinksOpen() {
        val n = note().copy(place = "24 Lê Văn Hưu, Hai Bà Trưng")
        assertEquals("24 Lê Văn Hưu, Hai Bà Trưng", NoteLinks.mapsQuery(n))
        assertEquals("Quán Hương Liên", NoteLinks.mapsQuery(note()))
        assertNull(NoteLinks.mapsQuery(note(kind = "read")))
        assertEquals("https://www.google.com/maps/search/?api=1&query=24+L%C3%AA+V%C4%83n", NoteLinks.mapsSearchUrl("24 Lê Văn"))
        assertEquals("geo:0,0?q=Ph%E1%BB%9F", NoteLinks.geoUri("Phở"))
        assertTrue(NoteLinks.isMapsLink("https://maps.app.goo.gl/abc"))
        assertFalse(NoteLinks.isMapsLink("https://vnexpress.net/a"))
        assertEquals("https://maps.app.goo.gl/abc", NoteLinks.openUrl(note()))
        assertNull(NoteLinks.openUrl(note(url = "intent://evil#Intent;end")))
        assertNull(NoteLinks.openUrl(note(url = "file:///etc/passwd")))
        assertNull(NoteLinks.openUrl(note(url = null)))
    }

    @Test
    fun listLineShowsKindValuesAndWhereItStands() {
        assertEquals("📍 Địa điểm · bún chả · Đã đến", note().noteKindLine())
        assertEquals("📍 Địa điểm · Chưa đến", note(attrs = emptyMap()).noteKindLine())
        assertEquals("📖 Đọc sau · mẹo · Chưa đọc", note(kind = "read", attrs = mapOf("topic" to "mẹo")).noteKindLine())
        assertEquals(listOf("bún chả"), note().noteValues())
        assertEquals("Đã đến", note().noteState())
        assertEquals("Chưa đọc", note(kind = "read", attrs = emptyMap()).noteState())
        assertEquals(emptyList<String>(), note(kind = null).noteValues())
        assertNull(note(kind = null).noteState())
        assertNull(note(kind = null).noteKindLine())
        assertNull(note(type = ItemType.TASK).noteKindLine())
    }

    @Test
    fun doneFlagTogglesOnlyWhereTheKindHasOne() {
        val read = note(kind = "read", attrs = mapOf("topic" to "mẹo"))
        assertEquals(mapOf("topic" to "mẹo", "read" to "1"), read.withNoteDone(true).attrs)
        assertEquals(mapOf("topic" to "mẹo"), read.withNoteDone(true).withNoteDone(false).attrs)
        assertEquals(note(kind = null), note(kind = null).withNoteDone(true))
    }

    @Test
    fun filterNarrowsByKindAndByNotYetDone() {
        val place = note()
        val readNew = note(kind = "read", attrs = mapOf("topic" to "mẹo"))
        val readDone = note(kind = "read", attrs = mapOf("read" to "1"))
        val plain = note(kind = null)
        val all = listOf(place, readNew, readDone, plain)
        assertEquals(all, NoteFilter().apply(all))
        assertEquals(listOf(readNew, readDone), NoteFilter(kind = NoteKind.READ).apply(all))
        assertEquals(listOf(readNew), NoteFilter(kind = NoteKind.READ, pending = true).apply(all))
        assertEquals("a plain note has nothing to finish", listOf(readNew), NoteFilter(pending = true).apply(all))
        assertEquals(listOf(NoteKind.PLACE, NoteKind.READ), NoteFilter.available(all))
    }

    @Test
    fun searchTextHasLinkKindNameAndTextValuesButNotFlags() {
        val text = note().noteSearchText()
        assertTrue(text.contains("https://maps.app.goo.gl/abc"))
        assertTrue(text.contains("Địa điểm"))
        assertTrue(text.contains("bún chả"))
        assertFalse(text.contains("1"))
        assertEquals("", note(kind = null).noteSearchText())
    }
}
