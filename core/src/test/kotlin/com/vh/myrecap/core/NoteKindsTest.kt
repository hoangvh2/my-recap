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
}
