package com.vh.myrecap.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** What a link's page says about itself (Open Graph / `<title>`), plus where the link finally led. */
data class LinkMeta(
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    val finalUrl: String? = null,
)

/**
 * Turns what another app shares (subject, text, links) into a ready-to-save note, so the person types as little
 * as possible: a title, the link, the page's own description and, for map links or "Địa chỉ:" lines, the place.
 * Pure Kotlin; the page lookup is [LinkPreview].
 */
object ShareIntake {
    const val MAX_TITLE = 120
    private const val MAX_DESCRIPTION = 500
    private const val MAX_TEXT = 20_000

    data class Draft(
        val title: String,
        /** The title is only a stand-in ("Link từ Facebook"); the page's own title replaces it when it arrives. */
        val titleGuessed: Boolean,
        /** What the sharer wrote, without the link. */
        val body: String,
        val url: String?,
        val source: String?,
        val place: String?,
        val description: String? = null,
    ) {
        /** What the note is about, guessed from the link and the place; the person can change it in the editor. */
        val kind: NoteKind? get() = kindOf(url, source, place)

        /** The note body: what was written, the page's description, then the link. A note with a kind keeps its link in [url]. */
        val details: String
            get() = listOfNotNull(
                body.takeIf { it.isNotBlank() && it != title },
                description?.takeIf { it.isNotBlank() && !body.contains(it) && it != title },
                url?.takeIf { kind == null }?.let { u -> (source?.let { "Nguồn: $it\n" } ?: "") + u },
            ).joinToString("\n\n")

        fun toItem(id: String, now: Long): Item {
            val k = kind
            return NoteKinds.clean(
                Item(
                    id = id,
                    type = ItemType.NOTE,
                    status = ItemStatus.OPEN,
                    title = title,
                    details = details,
                    place = place,
                    createdAt = now,
                    kind = k?.key,
                    url = url.takeIf { k != null },
                    attrs = if (k == NoteKind.READ && source != null) mapOf("source" to source) else emptyMap(),
                ),
            )
        }
    }

    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val TRAILING = charArrayOf('.', ',', ';', ':', ')', ']', '!', '?', '”', '’')
    private val ADDRESS_LINE = Regex("""^\s*(?:📍|🏠|📌|địa chỉ|dia chi|đc|add(?:ress)?)\s*[:：\-–]?\s*(.{6,})$""", RegexOption.IGNORE_CASE)

    /** [texts] are the shared `EXTRA_TEXT`s (several for SEND_MULTIPLE). */
    fun parse(subject: String?, texts: List<CharSequence?>): Draft {
        val raw = texts.filterNotNull().joinToString("\n").take(MAX_TEXT)
        val urls = URL_RE.findAll(raw).map { it.value.trimEnd(*TRAILING) }.toList().distinct()
        val url = urls.firstOrNull()
        val body = URL_RE.replace(raw, " ")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val subj = subject?.trim()?.takeIf { it.isNotEmpty() && !URL_RE.containsMatchIn(it) && it != body }
        val source = url?.let { sourceOf(it) }

        val firstLine = body.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
        val title = subj?.let { clip(it) } ?: firstLine?.let { clip(it) }
        // Two links shared at once: keep the extra ones with the text so nothing is lost.
        val extraLinks = urls.drop(1).joinToString("\n")
        val textPart = when {
            subj != null || firstLine == null -> body
            // The first line became the title; keep it whole in the body when the title had to be cut.
            title!!.length < firstLine.length -> body
            else -> body.lines().drop(1).joinToString("\n")
        }
        val fullBody = listOf(textPart, extraLinks).filter { it.isNotBlank() }.joinToString("\n")
        val place = url?.let { mapsPlace(it) } ?: addressIn(body)
        return Draft(
            title = title ?: place ?: source?.let { "Link từ $it" } ?: "Nội dung chia sẻ",
            titleGuessed = title == null && place == null,
            body = fullBody,
            url = url,
            source = source,
            place = place,
        )
    }

    /** Adds what the page said; the person's own words in [draft] always win over the page's. */
    fun enrich(draft: Draft, meta: LinkMeta): Draft {
        val pageTitle = meta.title?.let(::cleanText)?.takeUnless { isGeneric(it) }?.let { clip(it) }
        val pageDescription = meta.description?.let(::cleanText)?.takeUnless { isGeneric(it) }?.take(MAX_DESCRIPTION)
        val place = draft.place
            ?: meta.finalUrl?.let { mapsPlace(it) }
            ?: pageDescription?.let { addressIn(it) }
            ?: if (draft.source == "Google Maps") pageTitle else null
        return draft.copy(
            title = if (draft.titleGuessed && pageTitle != null) pageTitle else draft.title,
            titleGuessed = draft.titleGuessed && pageTitle == null,
            description = pageDescription,
            source = draft.source ?: meta.siteName?.let(::cleanText),
            place = place,
        )
    }

    /**
     * Guesses the kind of note a shared link makes: a map link or an address is a place, YouTube/TikTok and
     * Facebook/Instagram reels are videos, any other page is something to read later. Shop links and a text with
     * no link stay plain notes (those kinds come later).
     */
    fun kindOf(url: String?, source: String?, place: String?): NoteKind? {
        if (source == "Google Maps") return NoteKind.PLACE
        if (url == null) return if (place != null) NoteKind.PLACE else null
        return when {
            source == "Shopee" -> null
            source == "YouTube" || source == "TikTok" || isSocialVideo(url, source) -> NoteKind.VIDEO
            place != null -> NoteKind.PLACE
            else -> NoteKind.READ
        }
    }

    private fun isSocialVideo(url: String, source: String?): Boolean =
        (source == "Facebook" || source == "Instagram") &&
            (hostOf(url) == "fb.watch" || Regex("""/(reels?|videos?|watch)(/|\?|$)|/share/[rv]/""").containsMatchIn(url.lowercase()))

    /** "Facebook", "YouTube"… or the bare host for anything else. */
    fun sourceOf(url: String): String? {
        val host = hostOf(url) ?: return null
        return when {
            host.endsWith("facebook.com") || host == "fb.com" || host == "fb.me" || host == "fb.watch" -> "Facebook"
            host.endsWith("youtube.com") || host == "youtu.be" -> "YouTube"
            host.endsWith("tiktok.com") -> "TikTok"
            host.endsWith("instagram.com") -> "Instagram"
            host.endsWith("threads.net") || host.endsWith("threads.com") -> "Threads"
            host.endsWith("shopee.vn") -> "Shopee"
            host == "maps.app.goo.gl" || host.startsWith("maps.google.") || (host.startsWith("google.") && url.contains("/maps")) ||
                host == "goo.gl" && url.contains("/maps") -> "Google Maps"
            else -> host
        }
    }

    fun hostOf(url: String): String? = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(url)
        ?.groupValues?.get(1)?.lowercase()?.removePrefix("www.")?.removePrefix("m.")

    /** The place a Google Maps link points at: `/maps/place/<name>/…` or `?q=<name>` (not bare coordinates). */
    fun mapsPlace(url: String): String? {
        if (sourceOf(url) != "Google Maps" && !url.contains("google.com/maps")) return null
        val fromPath = Regex("""/maps/place/([^/@?]+)""").find(url)?.groupValues?.get(1)
        val fromQuery = Regex("""[?&](?:q|query)=([^&#]+)""").find(url)?.groupValues?.get(1)
        val name = (fromPath ?: fromQuery)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()
        return name?.takeIf { it.isNotEmpty() && !Regex("""^-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?$""").matches(it) }?.take(200)
    }

    /** A line like "📍 12 Nguyễn Trãi, Q.1" or "Địa chỉ: …" in shared text. */
    fun addressIn(text: String): String? = text.lineSequence()
        .mapNotNull { ADDRESS_LINE.find(it)?.groupValues?.get(1)?.trim() }
        .firstOrNull()?.take(200)

    private fun clip(s: String): String {
        val one = s.trim().replace(Regex("\\s+"), " ")
        if (one.length <= MAX_TITLE) return one
        val cut = one.take(MAX_TITLE - 1)
        return cut.substringBeforeLast(' ', cut).trimEnd() + "…"
    }

    private fun cleanText(s: String) = s.trim().replace(Regex("[ \\t]+"), " ")

    /** Login walls and site names that say nothing about the page. */
    private fun isGeneric(s: String): Boolean {
        val l = s.lowercase()
        return l == "facebook" || l == "youtube" || l == "tiktok" || l == "instagram" ||
            l.contains("log in") || l.contains("log into") || l.contains("đăng nhập") || l.contains("login") ||
            l.contains("sign up") || l.contains("just a moment")
    }
}

/** Reads a link's page title and description. Best effort: any failure is just "nothing found". */
object LinkPreview {
    private const val MAX_BYTES = 300_000
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    // Social sites show their public preview tags to link-preview bots, not to a plain browser (login wall).
    private const val BOT_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    fun fetch(url: String, timeoutMs: Int = 6_000): LinkMeta? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            val social = ShareIntake.sourceOf(url) in setOf("Facebook", "Instagram", "Threads")
            conn.setRequestProperty("User-Agent", if (social) BOT_UA else BROWSER_UA)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            conn.setRequestProperty("Accept-Language", "vi,en;q=0.8")
            val code = conn.responseCode
            val final = conn.url.toString()
            val type = conn.contentType.orEmpty().lowercase()
            if (code !in 200..299 || (type.isNotEmpty() && !type.contains("html"))) {
                // Still useful: a short map link tells the place by where it redirects to.
                LinkMeta(finalUrl = final).takeIf { final != url }
            } else {
                val bytes = conn.inputStream.use { s ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (buf.size() < MAX_BYTES) {
                        val n = s.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                parseHtml(bytes.toString(Charsets.UTF_8), final)
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    fun parseHtml(html: String, finalUrl: String? = null): LinkMeta {
        val head = html.substringBefore("</head>", html.take(MAX_BYTES))
        fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            val k = Regex.escape(key)
            // property/name may come before or after content.
            Regex("""<meta\s+[^>]*?(?:property|name)\s*=\s*["']$k["'][^>]*?content\s*=\s*(?:"([^"]*)"|'([^']*)')""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(head)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                ?: Regex("""<meta\s+[^>]*?content\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*?(?:property|name)\s*=\s*["']$k["']""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(head)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        }?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
        val titleTag = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(head)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
        return LinkMeta(
            title = meta("og:title", "twitter:title") ?: titleTag,
            description = meta("og:description", "twitter:description", "description"),
            siteName = meta("og:site_name"),
            finalUrl = finalUrl,
        )
    }

    fun decodeEntities(s: String): String {
        val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> named[e]
            } ?: m.value
        }
    }
}
