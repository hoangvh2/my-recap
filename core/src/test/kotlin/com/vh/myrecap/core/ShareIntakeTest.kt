package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakeTest {
    private fun parse(subject: String?, vararg text: String) = ShareIntake.parse(subject, text.toList())

    @Test
    fun bareFacebookLinkGetsAStandInTitleAndTheSource() {
        val d = parse(null, "https://www.facebook.com/share/v/1AbCdEf/")
        assertEquals("Link từ Facebook", d.title)
        assertTrue(d.titleGuessed)
        assertEquals("Facebook", d.source)
        assertEquals("", d.details)
        assertEquals(NoteKind.VIDEO, d.kind)
    }

    @Test
    fun pageTitleAndDescriptionReplaceTheStandIn() {
        val d = ShareIntake.enrich(
            parse(null, "https://fb.watch/abc/"),
            LinkMeta(title = "Review quán bún bò Huế", description = "Quán ngon.\nĐịa chỉ: 12 Nguyễn Trãi, Quận 1", siteName = "Facebook"),
        )
        assertEquals("Review quán bún bò Huế", d.title)
        assertFalse(d.titleGuessed)
        assertEquals("12 Nguyễn Trãi, Quận 1", d.place)
        assertTrue(d.details.startsWith("Quán ngon."))
        assertEquals(NoteKind.VIDEO, d.kind)
        assertEquals("https://fb.watch/abc/", d.url)
        assertFalse(d.details.contains("https://fb.watch/abc/"))
    }

    @Test
    fun facebookLoginWallIsNotATitle() {
        val d = ShareIntake.enrich(parse(null, "https://www.facebook.com/x/posts/1"), LinkMeta(title = "Facebook", description = "Log into Facebook"))
        assertEquals("Link từ Facebook", d.title)
        assertTrue(d.titleGuessed)
        assertNull(d.description)
    }

    @Test
    fun sharedWordsWinOverThePage() {
        val d = ShareIntake.enrich(parse("Quán cà phê Q7", "https://example.com/a"), LinkMeta(title = "Trang khác"))
        assertEquals("Quán cà phê Q7", d.title)
    }

    @Test
    fun textBeforeTheLinkBecomesTitleAndBody() {
        val d = parse(null, "Quán phở ngon quá\nHẻm 5 Lê Lợi https://youtu.be/xyz.")
        assertEquals("Quán phở ngon quá", d.title)
        assertEquals("Hẻm 5 Lê Lợi", d.body)
        assertEquals("https://youtu.be/xyz", d.url)
        assertEquals("YouTube", d.source)
    }

    @Test
    fun longTextIsCutAtAWordButKeptWhole() {
        val text = "từ ".repeat(80).trim()
        val d = parse(null, text)
        assertTrue(d.title.length <= ShareIntake.MAX_TITLE)
        assertTrue(d.title.endsWith("…"))
        assertEquals(text, d.body)
    }

    @Test
    fun subjectIsTheTitleAndPlainUrlSubjectIsIgnored() {
        assertEquals("Tin hay", parse("Tin hay", "https://a.vn/x").title)
        assertEquals("Link từ a.vn", parse("https://a.vn/x", "https://a.vn/x").title)
    }

    @Test
    fun extraLinksAreKept() {
        val d = parse("T", "https://a.vn/1", "https://a.vn/2")
        assertEquals("https://a.vn/1", d.url)
        assertTrue(d.details.contains("https://a.vn/2"))
    }

    @Test
    fun mapsLinksGiveThePlace() {
        val d = parse(null, "https://www.google.com/maps/place/B%C3%BAn+b%C3%B2+Hu%E1%BA%BF+Nam+Giao/@10.77,106.69,17z")
        assertEquals("Bún bò Huế Nam Giao", d.place)
        assertEquals("Bún bò Huế Nam Giao", d.title)
        assertEquals("Google Maps", d.source)
        assertNull(ShareIntake.mapsPlace("https://www.google.com/maps?q=10.77,106.69"))
        assertEquals("Phở Lệ", ShareIntake.mapsPlace("https://maps.google.com/?q=Ph%E1%BB%9F+L%E1%BB%87"))
    }

    @Test
    fun shortMapsLinkIsResolvedByRedirect() {
        val d = ShareIntake.enrich(
            parse(null, "https://maps.app.goo.gl/AbC123"),
            LinkMeta(finalUrl = "https://www.google.com/maps/place/Qu%C3%A1n+A/@1,2,17z"),
        )
        assertEquals("Quán A", d.place)
    }

    @Test
    fun addressLinesInSharedTextFillThePlace() {
        assertEquals("5 Lê Lợi, Q.1", parse("Quán X", "Ngon\n📍 5 Lê Lợi, Q.1\nhttps://fb.me/a").place)
        assertEquals("5 Lê Lợi", parse(null, "Địa chỉ: 5 Lê Lợi").place)
        assertNull(parse(null, "Tin thường").place)
    }

    @Test
    fun htmlMetaIsReadInEitherAttributeOrder() {
        val html = """<html><head><title>Fallback</title>
            <meta property="og:title" content="Tiêu đề &amp; &quot;hay&quot;">
            <meta content="Mô tả &#7843;nh" property="og:description">
            <meta property='og:site_name' content='Foo'></head><body></body></html>"""
        val m = LinkPreview.parseHtml(html, "https://x")
        assertEquals("Tiêu đề & \"hay\"", m.title)
        assertEquals("Mô tả ảnh", m.description)
        assertEquals("Foo", m.siteName)
        assertEquals("Fallback", LinkPreview.parseHtml("<head><title> Fallback </title></head>").title)
    }

    @Test
    fun draftBecomesAnOpenNote() {
        val i = parse("Quán A", "https://a.vn").toItem("id1", 5L)
        assertEquals(ItemType.NOTE, i.type)
        assertEquals(ItemStatus.OPEN, i.status)
        assertEquals("Quán A", i.title)
    }

    private fun item(vararg text: String) = parse(null, *text).toItem("id", 1L)

    @Test
    fun mapsLinkMakesAPlaceNoteWithItsLink() {
        val i = item("https://www.google.com/maps/place/Qu%C3%A1n+A/@1,2,17z")
        assertEquals("place", i.kind)
        assertEquals("https://www.google.com/maps/place/Qu%C3%A1n+A/@1,2,17z", i.url)
        assertEquals("Quán A", i.place)
        assertFalse("the link is kept in url, not repeated in the body", i.details.contains("http"))
    }

    @Test
    fun facebookPostIsReadLaterWithItsSourceAndReelsAreVideos() {
        val post = item("Mẹo hay\nhttps://www.facebook.com/groups/1/posts/2/")
        assertEquals("read", post.kind)
        assertEquals(mapOf("source" to "Facebook"), post.attrs)
        assertEquals("https://www.facebook.com/groups/1/posts/2/", post.url)
        assertEquals("video", item("https://www.facebook.com/reel/123").kind)
        assertEquals("video", item("https://fb.watch/abc/").kind)
        assertEquals("video", item("https://youtu.be/xyz").kind)
        assertEquals("video", item("https://www.tiktok.com/@a/video/1").kind)
    }

    @Test
    fun anAddressInAPostMakesItAPlaceAndOtherPagesAreRead() {
        assertEquals("place", item("Quán ngon\n📍 5 Lê Lợi, Q.1\nhttps://www.facebook.com/posts/9").kind)
        assertEquals("read", item("https://vnexpress.net/bai-viet-1.html").kind)
    }

    @Test
    fun shopLinksAndPlainTextStayPlainNotes() {
        val shop = item("https://shopee.vn/abc")
        assertNull(shop.kind)
        assertTrue(shop.details.contains("https://shopee.vn/abc"))
        assertNull(item("Nhớ mua sữa").kind)
        assertNull(item("Nhớ mua sữa").url)
    }
}
